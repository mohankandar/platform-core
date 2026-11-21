package com.documentplatform.licensing;

/** How a licence runs out. Chosen when the licence is issued. */
public enum LicenceType {
    /** The default. Has an expiry date, then a 30-day grace period, then intake stops. */
    SUBSCRIPTION,
    /**
     * For internal use. No expiry date and never enters grace. Has an "updates until" date: releases dated after it are
     * not covered (the installed version keeps working).
     */
    PERPETUAL
}
