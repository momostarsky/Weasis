/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.rt;

import static org.opencv.core.Core.add;
import static org.opencv.core.Core.minMaxLoc;
import static org.opencv.core.Core.multiply;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.DoubleStream;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.img.util.DicomUtils;
import org.joml.Vector3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.media.data.ImageElement;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.MediaSeries.MEDIA_POSITION;
import org.weasis.core.ui.model.graphic.imp.seg.SegContour;
import org.weasis.core.util.MathUtil;
import org.weasis.core.util.StringUtil;
import org.weasis.dicom.codec.DcmMediaReader;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomMediaIO;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.opencv.seg.Segment;
import org.weasis.opencv.seg.SegmentCategory;

/**
 * RtSet is a collection of linked DICOM-RT entities that form the whole treatment case (Plans,
 * Doses, StructureSets)
 *
 * @author Tomas Skripcak
 */
public class RtSet {

  private static final Logger LOGGER = LoggerFactory.getLogger(RtSet.class);

  private final List<MediaElement> rtElements = new ArrayList<>();
  private final LinkedHashSet<StructureSet> structures = new LinkedHashSet<>();
  private final LinkedHashSet<Plan> plans = new LinkedHashSet<>();
  private final DicomSeries series;
  private final Map<String, ArrayList<Contour>> contourMap = new HashMap<>();

  private Image patientImage;

  private int structureFillTransparency = 115;
  private int isoFillTransparency = 70;
  boolean forceRecalculateDvh = false;
  private double doseMax;

  public RtSet(DicomSeries series, List<RtSpecialElement> rtElements) {
    this.series = Objects.requireNonNull(series);
    this.rtElements.addAll(Objects.requireNonNull(rtElements));
    Object image = series.getMedia(MEDIA_POSITION.MIDDLE, null, null);
    this.doseMax = 0.0;
    if (image instanceof DicomImageElement dicomImageElement) {
      this.patientImage = new Image(dicomImageElement);
      this.patientImage.setImageLUT(this.calculatePixelLookupTable(dicomImageElement));
    }
  }

  public double getDoseMax() {
    // Initialise max dose once dose images are available
    if (this.series.size(null) > 1 && doseMax < 0.01) {
      for (DicomImageElement img : series.getMedias(null, null)) {
        try {
          Core.MinMaxLocResult minMaxLoc = minMaxLoc(img.getImage().toMat());
          if (doseMax < minMaxLoc.maxVal) {
            doseMax = minMaxLoc.maxVal;
          }
        } catch (Exception e) {
          System.out.println("Error: " + e.getMessage());
        }
      }
    }

    return doseMax;
  }

  public DicomSeries getSeries() {
    return series;
  }

  public Image getPatientImage() {
    return patientImage;
  }

  public void reloadRtCase(boolean forceRecalculateDvh) {
    this.forceRecalculateDvh = forceRecalculateDvh;

    // First initialise all RTSTRUCT
    for (MediaElement rt : this.rtElements) {
      if (rt instanceof StructureSet structureSet) {
        structureSet.initContours(patientImage.getImage());
        structures.add(structureSet);
      }
    }

    // Then initialise all RTPLAN
    for (MediaElement rt : this.rtElements) {
      // Photon and Proton Plans
      if (rt instanceof Plan plan) {
        initPlan(plan);
      }
    }

    // Then initialise all RTDOSE
    for (MediaElement rt : this.rtElements) {
      String sopUID = TagD.getTagValue(rt, Tag.SOPClassUID, String.class);
      if (UID.RTDoseStorage.equals(sopUID)) {
        initDose(rt);
      }
    }

    // Plans and doses are loaded
    if (!plans.isEmpty()) {
      for (Plan plan : plans) {

        // Init Dose LUTs
        for (Dose dose : plan.getDoses()) {
          dose.setDoseMmLUT(patientImage.getImageLUT());
          dose.initialiseDoseGridToImageGrid(patientImage);
        }

        this.initIsoDoses(plan);

        // Re-init DVHs
        for (Dose dose : plan.getDoses()) {
          if (getDoseMax() > 0) {

            // For all ROIs
            for (StructRegion region : getFirstStructure().getSegAttributes().values()) {
              SegmentCategory category = region.getCategory();

              // If DVH exists for the structure and setting always recalculate is false
              Dvh structureDvh = dose.get(category.getId());

              // Re-calculate DVH if it does not exist or if it is provided and force recalculation
              // is set up
              if (structureDvh == null
                  || (structureDvh.getDvhSource().equals(DataSource.PROVIDED)
                      && this.forceRecalculateDvh)) {
                structureDvh = this.initCalculatedDvh(region, dose);
                dose.put(category.getId(), structureDvh);
              }
              // Otherwise, read provided DVH
              else {
                // Absolute volume is provided and defined in DVH (in cm^3) so use it
                if (structureDvh.getDvhSource().equals(DataSource.PROVIDED)
                    && structureDvh.getDvhVolumeUnit().equals("CM3")) {
                  region.setVolume(structureDvh.getDvhData()[0]);
                }
              }

              // Associate Plan with DVH to make it accessible from DVH
              structureDvh.setPlan(plan);
              // Associate DVH with structure to make this data accessible from structure
              region.setDvh(structureDvh);

              // Display volume
              double volume = region.getVolume();
              String source = region.getVolumeSource().toString();
              if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "Structure: {}, {} Volume: {} cm^3",
                    region.getCategory().getLabel(),
                    source,
                    String.format("%.4f", volume));

                // If plan is loaded with prescribed treatment dose calculate DVH statistics
                String relativeMinDose =
                    String.format(
                        "Structure: %s, %s Min Dose: %.3f %%",
                        region.getCategory().getLabel(),
                        structureDvh.getDvhSource(),
                        RtSet.calculateRelativeDose(
                            structureDvh.getDvhMinimumDoseCGy(), plan.getRxDose()));
                String relativeMaxDose =
                    String.format(
                        "Structure: %s, %s Max Dose: %.3f %%",
                        region.getCategory().getLabel(),
                        structureDvh.getDvhSource(),
                        RtSet.calculateRelativeDose(
                            structureDvh.getDvhMaximumDoseCGy(), plan.getRxDose()));
                String relativeMeanDose =
                    String.format(
                        "Structure:  %s,  %s Mean Dose: %.3f %%",
                        region.getCategory().getLabel(),
                        structureDvh.getDvhSource(),
                        RtSet.calculateRelativeDose(
                            structureDvh.getDvhMeanDoseCGy(), plan.getRxDose()));
                LOGGER.debug(relativeMinDose);
                LOGGER.debug(relativeMaxDose);
                LOGGER.debug(relativeMeanDose);
              }
            }
          }
        }
      }
    }
  }

  @Override
  public int hashCode() {
    return series.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    RtSet other = (RtSet) obj;
    return (!series.equals(other.series));
  }

  /**
   * Initialise RTPLAN objects
   *
   * @param plan the RTPLAN dicom object
   */
  private void initPlan(Plan plan) {

    Attributes dcmItems = plan.getMediaReader().getDicomObject();
    if (dcmItems != null) {
      String planSopInstanceUid = dcmItems.getString(Tag.SOPInstanceUID);
      plan.setSopInstanceUid(planSopInstanceUid);

      // Some plans exist (probably dummy plans)
      if (!plans.isEmpty()) {

        // Plan with such SOP already exists
        boolean planWithSopExists =
            getPlans().stream().anyMatch(p -> p.getSopInstanceUid().equals(planSopInstanceUid));
        if (planWithSopExists) {
          // Plan do not have associated RTSpecialElement = it is dummy
          Optional<Plan> opPlan =
              getPlans().stream()
                  .filter(p -> p.getSopInstanceUid().equals(planSopInstanceUid))
                  .findFirst();

          if (opPlan.isPresent() && opPlan.get().getKey() == null) {
            plan = opPlan.get();
            // Remove the dummy from the set
            plans.remove(plan);
          }
        }
      }

      plan.setLabel(dcmItems.getString(Tag.RTPlanLabel));
      plan.setName(dcmItems.getString(Tag.RTPlanName));
      plan.setDescription(dcmItems.getString(Tag.RTPlanDescription));
      plan.setDate(dcmItems.getDate(Tag.RTPlanDateAndTime));
      plan.setGeometry(dcmItems.getString(Tag.RTPlanGeometry));

      plan.setRxDose(0.0);

      // When DoseReferenceSequence is defined - get prescribed dose from there (in cGy unit)
      Sequence doseRefSeq = dcmItems.getSequence(Tag.DoseReferenceSequence);
      if (doseRefSeq != null) {
        for (Attributes doseRef : doseRefSeq) {

          String doseRefStructType = doseRef.getString(Tag.DoseReferenceStructureType);

          // Prescribed dose in Gy
          Double targetDose =
              DicomUtils.getDoubleFromDicomElement(doseRef, Tag.TargetPrescriptionDose, null);

          if (targetDose != null) {

            // DICOM specifies prescription dose In Gy -> convert to cGy
            double rxDose = targetDose * 100;

            // POINT (dose reference point specified as ROI)
            if ("POINT".equals(doseRefStructType)) {
              // NOOP
              LOGGER.info("Not supported: dose reference point specified as ROI");
            }

            // VOLUME structure is associated with dose (dose reference volume specified as ROI)
            // SITE structure is associated with dose (dose reference clinical site)
            // COORDINATES (point specified by Dose Reference Point Coordinates (300A,0018))
            else if ("VOLUME".equals(doseRefStructType)
                || "SITE".equals(doseRefStructType)
                || "COORDINATES".equals(doseRefStructType)) {

              // Keep the highest prescribed dose
              if (plan.getRxDose() != null && rxDose > plan.getRxDose()) {
                plan.setRxDose(rxDose);

                // Add user defined dose description to plan name
                String doseRefDesc = doseRef.getString(Tag.DoseReferenceDescription);
                if (StringUtil.hasText(doseRefDesc)) {
                  plan.appendName(doseRefDesc);
                }
              }
            }
          }
          // TODO: if target prescribed dose is not defined it should be possible to get the dose
          // value from
          // Dose Reference Point Coordinates
        }
      }

      // When fraction group sequence is defined get prescribed dose from there (in cGy unit)
      if (MathUtil.isEqualToZero(plan.getRxDose())) {
        Attributes fractionGroup = dcmItems.getNestedDataset(Tag.FractionGroupSequence);
        Integer fx =
            DicomUtils.getIntegerFromDicomElement(
                fractionGroup, Tag.NumberOfFractionsPlanned, null);
        if (fx != null) {
          Sequence refBeamSeq = fractionGroup.getSequence(Tag.ReferencedBeamSequence);
          if (refBeamSeq != null) {
            for (Attributes beam : refBeamSeq) {
              if (beam.contains(Tag.BeamDose) && beam.containsValue(Tag.BeamDose)) {
                Double rxDose = plan.getRxDose();
                Double beamDose = DicomUtils.getDoubleFromDicomElement(beam, Tag.BeamDose, null);
                if (beamDose != null && rxDose != null) {
                  plan.setRxDose(rxDose + (beamDose * fx * 100));
                }
              }
            }
          }
        }
      }

      plans.add(plan);
    }
  }

  /**
   * Initialise RTDOSE objects
   *
   * @param rtElement RTDOSE dicom object
   */
  private void initDose(MediaElement rtElement) {
    Attributes dcmItems = ((DcmMediaReader) rtElement.getMediaReader()).getDicomObject();
    if (dcmItems != null) {

      String sopInstanceUID = dcmItems.getString(Tag.SOPInstanceUID);

      // Dose is Referencing Plan
      Plan plan = null;
      String referencedPlanUid = "";
      Sequence refPlanSeq = dcmItems.getSequence(Tag.ReferencedRTPlanSequence);
      if (refPlanSeq != null) {
        for (Attributes refRtPlanSeq : refPlanSeq) {
          referencedPlanUid = refRtPlanSeq.getString(Tag.ReferencedSOPInstanceUID);
        }
      }

      // Plan is already loaded
      if (!plans.isEmpty()) {
        String finalReferencedPlanUid = referencedPlanUid;
        Optional<Plan> opPlan =
            getPlans().stream()
                .filter(p -> p.getSopInstanceUid().equals(finalReferencedPlanUid))
                .findFirst();
        if (opPlan.isPresent()) {
          plan = opPlan.get();
        }
      }
      // Dummy plan will be created
      else {
        plan = new Plan((DicomMediaIO) rtElement.getMediaReader());
        plan.setSopInstanceUid(referencedPlanUid);
        plans.add(plan);
      }

      // Dose for plan
      if (plan != null) {
        Dose rtDose = null;

        // Dose object with such SOP already exists, use it
        if (!plan.getDoses().isEmpty()) {
          Optional<Dose> opPlan =
              plan.getDoses().stream()
                  .filter(i -> i.getSopInstanceUid().equals(sopInstanceUID))
                  .findFirst();
          if (opPlan.isPresent()) {
            rtDose = opPlan.get();
          }

        }
        // Create a new dose object
        else {
          rtDose = new Dose(series);
          plan.getDoses().add(rtDose);

          rtDose.setSopInstanceUid(sopInstanceUID);
          rtDose.setImagePositionPatient(dcmItems.getDoubles(Tag.ImagePositionPatient));
          rtDose.setComment(dcmItems.getString(Tag.DoseComment));
          rtDose.setDoseUnit(dcmItems.getString(Tag.DoseUnits));
          rtDose.setDoseType(dcmItems.getString(Tag.DoseType));
          rtDose.setDoseSummationType(dcmItems.getString(Tag.DoseSummationType));
          rtDose.setGridFrameOffsetVector(dcmItems.getDoubles(Tag.GridFrameOffsetVector));
          rtDose.setDoseGridScaling(dcmItems.getDouble(Tag.DoseGridScaling, 0.0));

          // Check whether DVH is included
          Sequence dvhSeq = dcmItems.getSequence(Tag.DVHSequence);
          if (dvhSeq != null) {

            for (Attributes dvhAttributes : dvhSeq) {

              // Need to refer to delineated contour
              Dvh rtDvh = null;
              Sequence dvhRefRoiSeq = dvhAttributes.getSequence(Tag.DVHReferencedROISequence);
              if (dvhRefRoiSeq == null) {
                continue;
              } else if (dvhRefRoiSeq.size() == 1) {
                rtDvh = new Dvh();
                Attributes dvhRefRoiAttributes = dvhRefRoiSeq.getFirst();
                rtDvh.setReferencedRoiNumber(
                    dvhRefRoiAttributes.getInt(Tag.ReferencedROINumber, -1));

                LOGGER.debug("Found DVH for ROI: {}", rtDvh.getReferencedRoiNumber());
              }

              if (rtDvh != null) {
                rtDvh.setDvhSource(DataSource.PROVIDED);
                // Convert Differential DVH to Cumulative
                if (dvhSeq.get(0).getString(Tag.DVHType).equals("DIFFERENTIAL")) {

                  LOGGER.info("Not supported: converting differential DVH to cumulative");

                  double[] data = dvhAttributes.getDoubles(Tag.DVHData);
                  if (data != null && data.length % 2 == 0) {

                    // X of histogram
                    double[] dose = new double[data.length / 2];

                    // Y of histogram
                    double[] volume = new double[data.length / 2];

                    // Separate the dose and volume values into distinct arrays
                    for (int i = 0; i < data.length; i = i + 2) {
                      dose[i] = data[i];
                      volume[i] = data[i + 1];
                    }

                    // Get the min and max dose in cGy
                    int minDose = (int) (dose[0] * 100);
                    int maxDose = (int) DoubleStream.of(dose).sum();

                    // Get volume values
                    double maxVolume = DoubleStream.of(volume).sum();

                    // Determine the dose values that are missing from the original data
                    double[] missingDose = new double[minDose];
                    for (int j = 0; j < minDose; j++) {
                      missingDose[j] = maxVolume;
                    }

                    // Cumulative dose - x of histogram
                    // Cumulative volume data - y of histogram
                    double[] cumVolume = new double[dose.length];
                    double[] cumDose = new double[dose.length];
                    for (int k = 0; k < dose.length; k++) {
                      cumVolume[k] =
                          DoubleStream.of(Arrays.copyOfRange(volume, k, dose.length)).sum();
                      cumDose[k] = DoubleStream.of(Arrays.copyOfRange(dose, 0, k)).sum() * 100;
                    }

                    // Interpolated dose data for 1 cGy bins (between min and max)
                    int[] interpDose = new int[maxDose + 1 - minDose];
                    int m = 0;
                    for (int l = minDose; l < maxDose + 1; l++) {
                      interpDose[m] = l;
                      m++;
                    }

                    // Interpolated volume data
                    double[] interpCumVolume = interpolate(interpDose, cumDose, cumVolume);

                    // Append the interpolated values to the missing dose values
                    double[] cumDvhData = new double[missingDose.length + interpCumVolume.length];
                    System.arraycopy(missingDose, 0, cumDvhData, 0, cumDvhData.length);
                    System.arraycopy(
                        interpCumVolume, 0, cumDvhData, missingDose.length, interpCumVolume.length);

                    rtDvh.setDvhData(cumDvhData);
                    rtDvh.setDvhNumberOfBins(cumDvhData.length);
                  }
                }
                // Cumulative
                else {
                  // "filler" values are included in DVH data array (every second is DVH value)
                  double[] data = dvhAttributes.getDoubles(Tag.DVHData);
                  if (data != null && data.length % 2 == 0) {
                    double[] newData = new double[data.length / 2];

                    int j = 0;
                    for (int i = 1; i < data.length; i = i + 2) {
                      newData[j] = data[i];
                      j++;
                    }

                    rtDvh.setDvhData(newData);
                  }

                  rtDvh.setDvhNumberOfBins(dvhAttributes.getInt(Tag.DVHNumberOfBins, -1));
                }

                // Always cumulative - differential was converted
                rtDvh.setType("CUMULATIVE");
                rtDvh.setDoseUnit(dvhAttributes.getString(Tag.DoseUnits));
                rtDvh.setDoseType(dvhAttributes.getString(Tag.DoseType));
                rtDvh.setDvhDoseScaling(dvhAttributes.getDouble(Tag.DVHDoseScaling, 1.0));
                rtDvh.setDvhVolumeUnit(dvhAttributes.getString(Tag.DVHVolumeUnits));
                // -1.0 means that it needs to be calculated later
                rtDvh.setDvhMinimumDose(dvhAttributes.getDouble(Tag.DVHMinimumDose, -1.0));
                rtDvh.setDvhMaximumDose(dvhAttributes.getDouble(Tag.DVHMaximumDose, -1.0));
                rtDvh.setDvhMeanDose(dvhAttributes.getDouble(Tag.DVHMeanDose, -1.0));

                rtDose.put(rtDvh.getReferencedRoiNumber(), rtDvh);
              }
            }
          }
        }
      }
    }
  }

  /** Initialise ISO dose levels */
  private void initIsoDoses(Plan plan) {
    // Init IsoDose levels for each dose
    for (Dose dose : plan.getDoses()) {

      // Plan has specified prescribed dose and IsoDoses have not been initialised for specific dose
      // yet
      if (plan.getRxDose() != null && dose.getIsoDoseSet().isEmpty()) {
        int doseMaxLevel =
            (int)
                calculateRelativeDose(
                    (getDoseMax() * dose.getDoseGridScaling() * 1000), plan.getRxDose());

        // Max and standard levels 102, 100, 98, 95, 90, 80, 70, 50, 30
        if (doseMaxLevel > 0) {
          dose.getIsoDoseSet()
              .put(
                  doseMaxLevel,
                  new IsoDoseRegion(
                      doseMaxLevel,
                      new Color(120, 0, 0, isoFillTransparency),
                      "Max", // NON-NLS
                      plan.getRxDose())); // NON-NLS
          dose.getIsoDoseSet()
              .put(
                  102,
                  new IsoDoseRegion(
                      102, new Color(170, 0, 0, isoFillTransparency), "", plan.getRxDose()));
          dose.getIsoDoseSet()
              .put(
                  100,
                  new IsoDoseRegion(
                      100, new Color(238, 69, 0, isoFillTransparency), "", plan.getRxDose()));
          dose.getIsoDoseSet()
              .put(
                  98,
                  new IsoDoseRegion(
                      98, new Color(255, 165, 0, isoFillTransparency), "", plan.getRxDose()));
          dose.getIsoDoseSet()
              .put(
                  95,
                  new IsoDoseRegion(
                      95, new Color(255, 255, 0, isoFillTransparency), "", plan.getRxDose()));
          dose.getIsoDoseSet()
              .put(
                  90,
                  new IsoDoseRegion(
                      90, new Color(0, 255, 0, isoFillTransparency), "", plan.getRxDose()));
          dose.getIsoDoseSet()
              .put(
                  80,
                  new IsoDoseRegion(
                      80, new Color(0, 139, 0, isoFillTransparency), "", plan.getRxDose()));
          dose.getIsoDoseSet()
              .put(
                  70,
                  new IsoDoseRegion(
                      70, new Color(0, 255, 255, isoFillTransparency), "", plan.getRxDose()));
          dose.getIsoDoseSet()
              .put(
                  50,
                  new IsoDoseRegion(
                      50, new Color(0, 0, 255, isoFillTransparency), "", plan.getRxDose()));
          dose.getIsoDoseSet()
              .put(
                  30,
                  new IsoDoseRegion(
                      30, new Color(0, 0, 128, isoFillTransparency), "", plan.getRxDose()));

          // Commented level just for testing
          //           dose.getIsoDoseSet().put(2, new IsoDoseLayer(new IsoDose(2, new Color(0, 0,
          // 111,
          //           isoFillTransparency), "", plan.getRxDose())));

          // Go through whole imaging grid (CT)
          for (DicomImageElement image : this.series.getMedias(null, null)) {
            // Image slice UID and position
            String uidKey = TagD.getTagValue(image, Tag.SOPInstanceUID, String.class);
            KeyDouble z = new KeyDouble(image.getSliceGeometry().getTLHC().z);

            for (IsoDoseRegion isoDoseLayer : dose.getIsoDoseSet().values()) {
              double isoDoseThreshold = isoDoseLayer.getAbsoluteDose();


              StructContour isoContour = dose.getIsoDoseContour(z, isoDoseThreshold, isoDoseLayer);

              // Create empty hash map of planes for IsoDose layer if there is none
              if (isoDoseLayer.getPlanes() == null) {
                isoDoseLayer.setPlanes(new HashMap<>());
              }

              // Create a new IsoDose contour plane for Z or select existing one
              // it will hold list of contours for that plane
              isoDoseLayer.getPlanes().computeIfAbsent(z, k -> new ArrayList<>()).add(isoContour);
              // For lookup from GUI use specific image UID
              if (StringUtil.hasText(uidKey)) {
                List<StructContour> pls =
                    dose.getIsoContourMap().computeIfAbsent(uidKey, l -> new ArrayList<>());
                pls.add(isoContour);
              }
            }
          }

          // When finished creation of iso contours plane data calculate the plane thickness
          for (IsoDoseRegion isoDoseLayer : dose.getIsoDoseSet().values()) {
            isoDoseLayer.setThickness(calculatePlaneThickness(isoDoseLayer.getPlanes()));
          }
        }
      }
    }
  }

  public Set<StructureSet> getStructures() {
    return structures;
  }

  public StructureSet getFirstStructure() {
    if (structures.isEmpty()) {
      return null;
    }
    return structures.getFirst();
  }

  public Map<String, ArrayList<Contour>> getContourMap() {
    return contourMap;
  }

  public Set<Plan> getPlans() {
    return plans;
  }

  public Plan getFirstPlan() {
    if (!plans.isEmpty()) {
      return this.plans.getFirst();
    }
    return null;
  }

  public List<MediaElement> getRtElements() {
    return rtElements;
  }

  public void setStructureFillTransparency(int value) {
    this.structureFillTransparency = value;
  }

  /**
   * Calculates the structure plane thickness
   *
   * @return structure plane thickness
   */
  static double calculatePlaneThickness(Map<KeyDouble, List<StructContour>> planesMap) {
    // Sort the list of z coordinates
    List<KeyDouble> planes = new ArrayList<>(planesMap.keySet());
    Collections.sort(planes);

    // Set maximum thickness as initial value
    double thickness = 10000;

    // Compare z of each two next to each other planes in order to find the minimal shift in z
    for (int i = 1; i < planes.size(); i++) {
      double newThickness = planes.get(i).getValue() - planes.get(i - 1).getValue();
      if (newThickness < thickness) {
        thickness = newThickness;
      }
    }

    // When no other than initial thickness was detected, set 0
    if (thickness > 9999) {
      thickness = 0.0;
    }

    return thickness;
  }

  private static double[] interpolate(
      int[] interpolatedX, double[] xCoordinates, double[] yCoordinates) {
    double[] interpolatedY = new double[interpolatedX.length];

    PolynomialSplineFunction psf = interpolate(xCoordinates, yCoordinates);

    for (int i = 0; i <= interpolatedX.length; ++i) {
      interpolatedY[0] = psf.value(interpolatedX[i]);
    }

    return interpolatedY;
  }

  public static PolynomialSplineFunction interpolate(double[] x, double[] y) {
    if (x.length != y.length || x.length < 2) {
      throw new IllegalStateException();
    }

    // Number of intervals
    int length = x.length - 1;

    // Slope of the lines between the data points
    final double[] m = new double[length];
    for (int i = 0; i < length; i++) {
      m[i] = (y[i + 1] - y[i]) / (x[i + 1] - x[i]);
    }

    final PolynomialFunction[] polynomials = new PolynomialFunction[length];
    final double[] coefficients = new double[2];
    for (int i = 0; i < length; i++) {
      coefficients[0] = y[i];
      coefficients[1] = m[i];
      polynomials[i] = new PolynomialFunction(coefficients);
    }

    return new PolynomialSplineFunction(x, polynomials);
  }

  /**
   * Calculated relative dose with respect to absolute planned dose
   *
   * @param dose absolute simulated dose in cGy
   * @param planDose absolute planned dose in cGy
   * @return relative dose in %
   */
  public static double calculateRelativeDose(double dose, double planDose) {
    return (100 / planDose) * dose;
  }

  public Dvh initCalculatedDvh(StructRegion region, Dose dose) {
    Dvh dvh = new Dvh();
    dvh.setReferencedRoiNumber(region.getCategory().getId());
    dvh.setDvhSource(DataSource.CALCULATED);
    dvh.setType("CUMULATIVE");
    dvh.setDoseUnit("CGY");
    dvh.setDvhVolumeUnit("CM3");
    dvh.setDvhDoseScaling(1.0);

    // Calculate differential DVH
    Mat difHistogram = calculateDifferentialDvh(region, dose);

    // Convert differential DVH to cumulative DVH
    double[] cumHistogram = convertDifferentialToCumulativeDvh(difHistogram);
    dvh.setDvhData(cumHistogram);
    dvh.setDvhNumberOfBins(cumHistogram.length);

    return dvh;
  }

  private Mat calculateDifferentialDvh(StructRegion region, Dose dose) {

    DicomImageElement doseImage = this.series.getMedia(MEDIA_POSITION.FIRST, null,null);
    Vector3d doseImageSpacing = doseImage.getSliceGeometry().getVoxelSpacing();
    double maxDose = getDoseMax() * dose.getDoseGridScaling() * 100;

    double volume = 0f;

    // Prepare empty histogram (vector of bins in cGy) for structure
    Mat histogram = new Mat((int) maxDose, 1, CvType.CV_32FC1);
    if (region.getPlanes() != null && !region.getPlanes().isEmpty()) {
      // Each bin in histogram represents 1 cGy
      for (int i = 0; i < histogram.rows(); i++) {
        histogram.put(i, 0, 0.0);
      }
    }

    // Go through all structure plane slices
    for (Entry<KeyDouble, List<StructContour>> entry : region.getPlanes().entrySet()) {
      KeyDouble z = entry.getKey();

      // Calculate the area for each contour in the current plane
      AbstractMap.SimpleImmutableEntry<Integer, Double> maxContour =
          region.calculateLargestContour(entry.getValue());
      int maxContourIndex = maxContour.getKey();

      // If dose plane does not exist for z, continue with next plane
      MediaElement dosePlane = dose.getDosePlaneBySlice(z.getValue());
      if (dosePlane == null) {
        continue;
      }

      // Calculate histogram for each contour on the plane
      for (int c = 0; c < entry.getValue().size(); c++) {

        SegContour contour = entry.getValue().get(c);

        Mat contourMask = calculateContourMask(dose.getDoseMmLUT(), contour);
        Mat hist = dose.getMaskedDosePlaneHist(z.getValue(), contourMask, (int) maxDose);

        double vol = 0;
        for (int i = 0; i < hist.rows(); i++) {
          vol +=
              hist.get(i, 0)[0] * (doseImageSpacing.x * doseImageSpacing.y * region.getThickness());
        }

        // If this is the largest contour
        if (c == maxContourIndex) {
          volume += vol;
          add(histogram, hist, histogram);
        }
        // TODO: Otherwise add or subtract depending on contour location
        else {

        }
      }
    }

    // Volume units are given in cm^3
    volume /= 1000;

    // Rescale the histogram to reflect the total volume
    double sumHistogram = 0.0;
    for (int i = 0; i < histogram.rows(); i++) {
      sumHistogram += histogram.get(i, 0)[0];
    }
    Scalar scalar = new Scalar(volume / (sumHistogram == 0.0 ? 1.0 : sumHistogram));
    multiply(histogram, scalar, histogram);

    // TODO: Remove the zero bins from the end of histogram

    return histogram;
  }

  private double[] convertDifferentialToCumulativeDvh(Mat difHistogram) {
    int size = difHistogram.rows();
    double[] cumDvh = new double[size];

    for (int i = 0; i < size; i++) {
      cumDvh[i] = 0;
      for (int j = i; j < size; j++) {
        cumDvh[i] += difHistogram.get(j, 0)[0];
      }
    }

    return cumDvh;
  }

  private AbstractMap.SimpleImmutableEntry<double[], double[]> calculatePixelLookupTable(
      DicomImageElement dicomImage) {

    double deltaI = dicomImage.getSliceGeometry().getVoxelSpacing().x;
    double deltaJ = dicomImage.getSliceGeometry().getVoxelSpacing().y;

    Vector3d rowDirection = dicomImage.getSliceGeometry().getRow();
    Vector3d columnDirection = dicomImage.getSliceGeometry().getColumn();

    Vector3d position = dicomImage.getSliceGeometry().getTLHC();

    // DICOM C.7.6.2.1 Equation C.7.6.2.1-1.
    double[][] m = {
      {rowDirection.x * deltaI, columnDirection.x * deltaJ, 0, position.x},
      {rowDirection.y * deltaI, columnDirection.y * deltaJ, 0, position.y},
      {rowDirection.z * deltaI, columnDirection.z * deltaJ, 0, position.z},
      {0, 0, 0, 1}
    };

    double[] x = new double[dicomImage.getImage().width()];
    // column index to the image plane.
    for (int i = 0; i < dicomImage.getImage().width(); i++) {
      double[][] data = new double[][] {{i}, {0}, {0}, {1}};
      x[i] = multiplyMatrix(m, data)[0][0];
    }

    double[] y = new double[dicomImage.getImage().height()];
    // row index to the image plane
    for (int j = 0; j < dicomImage.getImage().height(); j++) {
      double[][] data = new double[][] {{0}, {j}, {0}, {1}};
      y[j] = multiplyMatrix(m, data)[1][0];
    }

    return new AbstractMap.SimpleImmutableEntry<>(x, y);
  }

  private double[][] multiplyMatrix(double[][] rotation, double[][] data) {
    final int nRows = rotation.length;
    final int nCols = data[0].length;
    final int nSum = rotation[0].length;
    final double[][] out = new double[nRows][nCols];
    for (int row = 0; row < nRows; ++row) {
      for (int col = 0; col < nCols; ++col) {
        double sum = 0;
        for (int i = 0; i < nSum; ++i) {
          sum += rotation[row][i] * data[i][col];
        }
        out[row][col] = sum;
      }
    }

    return out;
  }

  // TODO: this has to consider all plan doses
  // public void getDoseValueForPixel(Plan plan, int pixelX, int pixelY, double z) {
  // if (this.dosePixLUT != null) {
  // // closest x
  // double[] xDistance = new double[this.dosePixLUT.getFirst().length];
  // for (int i = 0; i < xDistance.length; i++) {
  // xDistance[i] = Math.abs(this.dosePixLUT.getFirst()[i] - pixelX);
  // }
  //
  // double minDistanceX = Arrays.stream(xDistance).min().getAsDouble();
  // int xDoseIndex = firstIndexOf(xDistance, minDistanceX, 0.001);
  //
  // // closest y
  // double[] yDistance = new double[this.dosePixLUT.getSecond().length];
  // for (int j = 0; j < yDistance.length; j++) {
  // yDistance[j] = Math.abs(this.dosePixLUT.getSecond()[j] - pixelY);
  // }
  //
  // double minDistanceY = Arrays.stream(yDistance).min().getAsDouble();
  // int yDoseIndex = firstIndexOf(yDistance, minDistanceY, 0.001);
  //
  // Dose dose = plan.getFirstDose();
  // if (dose != null) {
  // MediaElement dosePlane = dose.getDosePlaneBySlice(z);
  // Double doseGyValue = ((DicomImageElement)dosePlane).getImage().get(xDoseIndex, yDoseIndex)[0] *
  // dose.getDoseGridScaling();
  // LOGGER.debug("X: " + pixelX + ", Y: " + pixelY + ", Dose: " + doseGyValue + " Gy / " +
  // calculateRelativeDose(doseGyValue * 100, this.getFirstPlan().getRxDose()) + " %");
  // }
  // }
  // }

  private Mat calculateContourMask(
      AbstractMap.SimpleImmutableEntry<double[], double[]> doseMmLUT, SegContour contour) {

    int cols = doseMmLUT.getKey().length;
    int rows = doseMmLUT.getValue().length;

    List<Point> list = new ArrayList<>();
    MatOfPoint2f mop = new MatOfPoint2f();
    for (Segment segment : contour.getSegmentList()) {
      for (Point2D point : segment) {
        list.add(new Point(point.getX(), point.getY()));
      }
      if (!segment.getChildren().isEmpty()) {
        for (Segment child : segment.getChildren()) {
          for (Point2D point : child) {
            list.add(new Point(point.getX(), point.getY()));
          }
        }
        // TODO recursive
      }
    }

    mop.fromList(list);

    Mat binaryMask = new Mat(rows, cols, CvType.CV_32FC1);

    for (int i = 0; i < rows; i++) {
      for (int j = 0; j < cols; j++) {
        double distance =
            Imgproc.pointPolygonTest(
                mop, new Point(doseMmLUT.getKey()[j], doseMmLUT.getValue()[i]), false);
        // TODO: Include the border line as well?
        if (distance > 0) {
          binaryMask.put(i, j, 255);
        } else {
          binaryMask.put(i, j, 0);
        }
      }
    }

    return binaryMask;
  }
}
