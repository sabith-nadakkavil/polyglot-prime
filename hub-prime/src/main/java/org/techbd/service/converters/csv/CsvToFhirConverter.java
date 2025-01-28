package org.techbd.service.converters.csv;

import java.sql.Date;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DecimalType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.techbd.model.csv.DemographicData;
import org.techbd.model.csv.QeAdminData;
import org.techbd.model.csv.ScreeningObservationData;
import org.techbd.model.csv.ScreeningProfileData;
import org.techbd.service.converters.shinny.BundleConverter;
import org.techbd.service.converters.shinny.IConverter;
import org.techbd.service.http.hub.prime.AppConfig;

import ca.uhn.fhir.context.FhirContext;

@Component
public class CsvToFhirConverter {

    private final AppConfig appConfig;
    private final List<IConverter> converters;
    private final BundleConverter bundleConverter;
    private static final Logger LOG = LoggerFactory.getLogger(CsvToFhirConverter.class.getName());

    public CsvToFhirConverter(AppConfig appConfig, BundleConverter bundleConverter, List<IConverter> converters) {
        this.appConfig = appConfig;
        this.converters = converters;
        this.bundleConverter = bundleConverter;
    }

    public String convert(DemographicData demographicData,
            QeAdminData qeAdminData, ScreeningProfileData screeningProfileData,
            List<ScreeningObservationData> screeningDataList, String interactionId) {
        Bundle bundle = null;
        try {
            LOG.info("CsvToFhirConvereter::convert - BEGIN for interactionId :{}", interactionId);
            bundle = bundleConverter.generateEmptyBundle(interactionId, appConfig.getIgVersion(), demographicData);
            LOG.debug("CsvToFhirConvereter::convert - Bundle entry created :{}", interactionId);
            LOG.debug("Conversion of resources - BEGIN for interactionId :{}", interactionId);
            List<BundleEntryComponent> converterEntries = addEntries(bundle, demographicData, screeningDataList,
                    qeAdminData, screeningProfileData, interactionId);
                    
            List<BundleEntryComponent> provenanceEntries = addBundleProvenance(
                    new HashMap<>(),
                    new ArrayList<>(),
                    screeningProfileData.getPatientMrIdValue(),
                    screeningProfileData.getEncounterId(),
                    Instant.now(),
                    Instant.now());

            addProvenenceParameter(bundle, converterEntries, provenanceEntries);

            LOG.debug("Conversion of resources - END for interactionId :{}", interactionId);
            LOG.info("CsvToFhirConvereter::convert - END for interactionId :{}", interactionId);
        } catch (Exception ex) {
            LOG.error("Exception in Csv conversion for interaction id : {}", interactionId, ex);
        }
        return FhirContext.forR4().newJsonParser().encodeResourceToString(bundle);
    }

    private List<BundleEntryComponent> addEntries(Bundle bundle, DemographicData demographicData,
            List<ScreeningObservationData> screeningObservationData,
            QeAdminData qeAdminData, ScreeningProfileData screeningProfileData, String interactionId) {
        List<BundleEntryComponent> entries = new ArrayList<>();
        Map<String, String> idsGenerated = new HashMap<>();

        // Iterate over the converters
        converters.stream().forEach(converter -> {
            try {
                // Attempt to process using the current converter
                entries.addAll(converter.convert(bundle, demographicData, qeAdminData, screeningProfileData,
                        screeningObservationData, interactionId, idsGenerated));
            } catch (Exception e) {
                // Log the error and continue with other converters
                LOG.error("Error occurred while processing converter: " + converter.getClass().getName(), e);
            }
        });

        return entries;
    }

    public List<Bundle.BundleEntryComponent> addBundleProvenance(
            final Map<String, Object> existingProvenance, // Use existingProvenance
            final List<String> bundleGeneratedFrom,
            final String patientMrnId,
            final String encounterId,
            final Instant initiatedAt,
            final Instant completedAt) throws Exception {

        Parameters parameters = new Parameters();

        parameters.addParameter()
                .setName("description")
                .setValue(new StringType(
                        "Bundle created from provided files for the given patientMrnId and encounterId"));

        // Handle validated files as a collection of parameters.
        for (String file : bundleGeneratedFrom) {
            parameters.addParameter().setName("validatedFiles").addPart()
                    .setName("file").setValue(new StringType(file));

        }

        parameters.addParameter()
                .setName("initiatedAt")
                .setValue(new DateTimeType(Date.from(initiatedAt)));
        parameters.addParameter()
                .setName("completedAt")
                .setValue(new DateTimeType(Date.from(completedAt)));
        parameters.addParameter()
                .setName("patientMrnId")
                .setValue(new StringType(patientMrnId));
        parameters.addParameter()
                .setName("encounterId")
                .setValue(new StringType(encounterId));

        Parameters.ParametersParameterComponent agentParameter = parameters.addParameter().setName("agent");
        Parameters.ParametersParameterComponent whoPart = agentParameter.addPart().setName("who");

        whoPart.addPart().setName("coding").addPart()
                .setName("system").setValue(new StringType("generator"));

        whoPart.getPart().get(0).addPart().setName("display").setValue(new StringType("TechByDesign"));

        // Process existingProvenance
        if (existingProvenance != null && !existingProvenance.isEmpty()) {

            for (Map.Entry<String, Object> entry : existingProvenance.entrySet()) {
                Object value = entry.getValue();
                switch (value) {
                    case String string -> parameters.addParameter()
                            .setName(entry.getKey())
                            .setValue(new StringType(string));
                    case Integer integer -> parameters.addParameter()
                            .setName(entry.getKey())
                            .setValue(new IntegerType(integer));
                    case Double aDouble -> parameters.addParameter()
                            .setName(entry.getKey())
                            .setValue(new DecimalType(aDouble));
                    case Boolean aBoolean -> parameters.addParameter()
                            .setName(entry.getKey())
                            .setValue(new BooleanType(aBoolean));
                    default -> // If not any primitive types store value as json string in a String Type.
                        parameters.addParameter()
                                .setName(entry.getKey())
                                .setValue(new StringType(value.toString()));
                }

            }
        }

        Bundle.BundleEntryComponent bundleEntryComponent = new Bundle.BundleEntryComponent()
                .setResource(parameters);

        List<Bundle.BundleEntryComponent> bundleEntryComponents = new ArrayList<>();
        bundleEntryComponents.add(bundleEntryComponent);
        return bundleEntryComponents;

    }

    private void addProvenenceParameter(Bundle bundle, List<BundleEntryComponent> converterEntries,
            List<BundleEntryComponent> provenanceEntries) {

        bundle.getEntry().addAll(converterEntries);
        bundle.getEntry().addAll(provenanceEntries);
    }
}