package com.tbridge.debt.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record TelefonoRequest(@NotBlank @Size(max = 40) String telefono) {}
