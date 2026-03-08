package io.github.ngirchev.aibot.rest.dto;

public record ChatRequestDto(String message, String assistantRole, String model, String email) {
} 