package com.poptsov.gameactivitymonitoringservice.dto;

import java.util.ArrayList;
import java.util.List;

public class PlayerHistory {
    private String nickname;
    private List<GameSession> sessions = new ArrayList<>();

    public PlayerHistory(String nickname) { this.nickname = nickname; }
    public String getNickname() { return nickname; }
    public List<GameSession> getSessions() { return sessions; }

    public static class GameSession {
        private String loginTime;
        private String logoutTime;

        public GameSession(String loginTime, String logoutTime) {
            this.loginTime = loginTime;
            this.logoutTime = logoutTime;
        }
        public String getLoginTime() { return loginTime; }
        public String getLogoutTime() { return logoutTime; }
        public void setLogoutTime(String logoutTime) { this.logoutTime = logoutTime; }
    }
}