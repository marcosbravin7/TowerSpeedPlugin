package com.towerspeed.timer;

public enum TimerState {
    IDLE("<gray>IDLE</gray>"),
    COUNTDOWN("<yellow>COUNTDOWN</yellow>"),
    RUNNING("<green>RUNNING</green>"),
    PAUSED("<gold>PAUSED</gold>"),
    FINISHED("<aqua>FINISHED</aqua>");

    private final String displayTag;

    TimerState(String displayTag) {
        this.displayTag = displayTag;
    }

    public String getDisplayTag() {
        return displayTag;
    }
}
