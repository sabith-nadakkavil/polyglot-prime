package org.techbd.service.fhir.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.techbd.util.fhir.CoreFHIRUtil;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.validation.FhirValidator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FhirBundleValidator {
    private static final Logger LOG = LoggerFactory.getLogger(FhirBundleValidator.class);
    private String baseFHIRUrl;
    private FhirContext fhirContext;
    private String igVersion;
    private String fhirProfileUrl;
    private FhirValidator fhirValidator;
    private String packagePath;

    public String getFhirProfileUrl() {
        return org.techbd.util.fhir.CoreFHIRUtil.getProfileUrl(baseFHIRUrl, CoreFHIRUtil.BUNDLE);
    }
}
