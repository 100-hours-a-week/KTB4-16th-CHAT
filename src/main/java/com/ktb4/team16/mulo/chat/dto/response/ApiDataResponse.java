package com.ktb4.team16.mulo.chat.dto.response;

public record ApiDataResponse<T>(String message, T data) {
}
