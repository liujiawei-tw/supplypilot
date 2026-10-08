package com.supplypilot.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTests {

    private static final Instant FIXED_TIME = Instant.parse("2026-10-08T00:00:00Z");

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new ExceptionTestController())
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(FIXED_TIME, ZoneOffset.UTC)))
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void invalidDtoReturnsStableValidationContract() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"", "quantity":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Request validation failed."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").value(FIXED_TIME.toString()))
                .andExpect(jsonPath("$.path").value("/test/validate"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("quantity"));
    }

    @Test
    void notFoundBusinessExceptionReturns404() throws Exception {
        assertBusinessError("not-found", 404, ErrorCode.MATERIAL_NOT_FOUND);
    }

    @Test
    void lifecycleBusinessExceptionReturns409() throws Exception {
        assertBusinessError("lifecycle", 409, ErrorCode.INVALID_PO_STATUS_TRANSITION);
    }

    @Test
    void inactiveBusinessExceptionReturns400() throws Exception {
        assertBusinessError("inactive", 400, ErrorCode.MATERIAL_INACTIVE);
    }

    @Test
    void apiSpecificationErrorCodesMapToTheirRequiredHttpStatuses() {
        assertThat(ErrorHttpStatusMapper.toHttpStatus(ErrorCode.NEGATIVE_QUANTITY))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorHttpStatusMapper.toHttpStatus(ErrorCode.INVALID_PO_STATUS))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorHttpStatusMapper.toHttpStatus(ErrorCode.DUPLICATE_RECEIVING))
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void constraintViolationReturns400WithoutImplementationDetails() throws Exception {
        mockMvc.perform(get("/test/constraint"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void optimisticLockReturnsConflict() throws Exception {
        mockMvc.perform(get("/test/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.message").value(
                        "The resource was modified by another request. Refresh and try again."));
    }

    @Test
    void dataIntegrityReturnsGenericConflictWithoutDatabaseDetails() throws Exception {
        String response = mockMvc.perform(get("/test/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .doesNotContain("supplier_material")
                .doesNotContain("uk_internal_constraint")
                .doesNotContain("SQL");
    }

    @Test
    void unexpectedExceptionReturnsGeneric500WithoutInternalDetails() throws Exception {
        String response = mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").value(FIXED_TIME.toString()))
                .andExpect(jsonPath("$.path").value("/test/unexpected"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .doesNotContain("sensitive-internal-detail")
                .doesNotContain("IllegalStateException")
                .doesNotContain("stackTrace");
    }

    @Test
    void existingMdcTraceIdIsReusedInResponse() throws Exception {
        MDC.put("traceId", "existing-trace-id");

        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.traceId").value("existing-trace-id"));
    }

    @Test
    void businessExceptionIsUncheckedAndRetainsErrorCode() {
        BusinessException exception = new BusinessException(ErrorCode.MATERIAL_NOT_FOUND);

        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MATERIAL_NOT_FOUND);
    }

    private void assertBusinessError(String endpoint, int status, ErrorCode errorCode) throws Exception {
        mockMvc.perform(get("/test/{endpoint}", endpoint))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(status))
                .andExpect(jsonPath("$.code").value(errorCode.name()))
                .andExpect(jsonPath("$.message").value(errorCode.getDefaultMessage()))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").value(FIXED_TIME.toString()))
                .andExpect(jsonPath("$.path").value("/test/" + endpoint))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @RestController
    @RequestMapping("/test")
    private static class ExceptionTestController {

        @PostMapping("/validate")
        void validate(@Valid @RequestBody ValidationRequest request) {
        }

        @GetMapping("/not-found")
        void notFound() {
            throw new BusinessException(ErrorCode.MATERIAL_NOT_FOUND);
        }

        @GetMapping("/lifecycle")
        void lifecycle() {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }

        @GetMapping("/inactive")
        void inactive() {
            throw new BusinessException(ErrorCode.MATERIAL_INACTIVE);
        }

        @GetMapping("/constraint")
        void constraint() {
            throw new ConstraintViolationException("internal.property detail", Set.of());
        }

        @GetMapping("/optimistic-lock")
        void optimisticLock() {
            throw new ObjectOptimisticLockingFailureException(Object.class, 1L);
        }

        @GetMapping("/data-integrity")
        void dataIntegrity() {
            throw new DataIntegrityViolationException(
                    "SQL constraint uk_internal_constraint on supplier_material");
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("sensitive-internal-detail");
        }
    }

    private record ValidationRequest(
            @NotBlank String name,
            @Positive int quantity
    ) {
    }
}
