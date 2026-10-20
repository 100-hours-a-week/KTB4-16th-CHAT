package com.ktb4.team16.mulo.chat.dto.response;

import java.util.List;

public record ApiValidationErrorResponse(String code, String message, List<Error> errors) {

    public record Error(String field, String code, String message) {
    }
}
