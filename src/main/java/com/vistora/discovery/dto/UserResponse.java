package com.vistora.discovery.dto;

import java.util.List;
import java.util.Map;

public class UserResponse {
    private String system;
    private String connectionName;
    private List<Map<String, Object>> users;

    public UserResponse() {}

    public UserResponse(String system, String connectionName, List<Map<String, Object>> users) {
        this.system = system;
        this.connectionName = connectionName;
        this.users = users;
    }

    public String getSystem() {
        return system;
    }

    public void setSystem(String system) {
        this.system = system;
    }

    public String getConnectionName() {
        return connectionName;
    }

    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    public List<Map<String, Object>> getUsers() {
        return users;
    }

    public void setUsers(List<Map<String, Object>> users) {
        this.users = users;
    }

    public static UserResponseBuilder builder() {
        return new UserResponseBuilder();
    }

    public static class UserResponseBuilder {
        private String system;
        private String connectionName;
        private List<Map<String, Object>> users;

        public UserResponseBuilder system(String system) {
            this.system = system;
            return this;
        }

        public UserResponseBuilder connectionName(String connectionName) {
            this.connectionName = connectionName;
            return this;
        }

        public UserResponseBuilder users(List<Map<String, Object>> users) {
            this.users = users;
            return this;
        }

        public UserResponse build() {
            return new UserResponse(this.system, this.connectionName, this.users);
        }
    }
}
