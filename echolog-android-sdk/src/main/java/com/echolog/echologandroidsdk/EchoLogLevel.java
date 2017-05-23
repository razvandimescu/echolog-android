package com.echolog.echologandroidsdk;

/**
 * Created by cozu on 23.05.2017.
 */
public enum EchoLogLevel {
    INFO("info"),
    EVENT("event"),
    WARNING("warn"),
    ERROR("error"),
    DEBUG("debug");

    private final String value;

    EchoLogLevel(String val) { this.value = val; }

    public String getValue() { return value; }
}
