package com.travel.planning.guard;

/** 防护判定结果（F90） */
public record GuardResult(boolean allowed, String reason) {

    public static GuardResult allow() {
        return new GuardResult(true, null);
    }

    public static GuardResult deny(String reason) {
        return new GuardResult(false, reason);
    }
}
