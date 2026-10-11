package com.supplypilot.ai.service;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class AiResponseGuard {
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    public boolean containsOnlyTrustedNumbers(String answer, String trustedFacts) {
        Set<String> trustedNumbers = numbers(trustedFacts);
        Matcher answerNumbers = NUMBER.matcher(answer);
        while (answerNumbers.find()) {
            if (!trustedNumbers.contains(answerNumbers.group())) return false;
        }
        return true;
    }

    private Set<String> numbers(String value) {
        Set<String> numbers = new HashSet<>();
        Matcher matcher = NUMBER.matcher(value);
        while (matcher.find()) numbers.add(matcher.group());
        return numbers;
    }
}
