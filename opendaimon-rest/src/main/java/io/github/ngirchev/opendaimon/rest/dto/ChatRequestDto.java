package io.github.ngirchev.opendaimon.rest.dto;

public record ChatRequestDto(String message, String assistantRole, String model, String email) {
} 