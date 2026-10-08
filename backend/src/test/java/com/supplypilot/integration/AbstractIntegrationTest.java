package com.supplypilot.integration;

import com.supplypilot.TestcontainersConfiguration;
import com.supplypilot.testsupport.FixedClockConfiguration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
public abstract class AbstractIntegrationTest {
}
