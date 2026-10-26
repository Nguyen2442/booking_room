package com.company.booking.booking;

import java.time.Instant;

/** Validated half-open interval [start, end). */
public record TimeWindow(Instant start, Instant end) {}
