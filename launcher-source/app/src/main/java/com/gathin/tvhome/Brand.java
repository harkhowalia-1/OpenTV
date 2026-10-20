package com.gathin.tvhome;

/**
 * The project's names: the TV is an OpenTV (Otv for short) running the OtvOS shell. Used wherever the
 * launcher describes the device (Settings, Apple Home, Bonjour, the companion apps). Android's own model
 * string (Build.MODEL) is part of the system image and stays as the manufacturer set it.
 */
final class Brand {
    static final String NAME = "OpenTV";
    static final String SHORT = "Otv";
    static final String OS = "OtvOS";
    static final String OS_VERSION = "1.0";
    static final String MODEL = "OpenTV";

    private Brand() {
    }
}
