package com.limy.util;

import java.util.ArrayList;
import java.util.List;

public class Task {
    private int id;
    private String subject;
    private String description;
    private String status;
    private List<Integer> blockedBy;
    private List<Integer> blocks;
    private String owner;
    private String worktree;
    private long updatedAt;

    public Task() {
        this.blockedBy = new ArrayList<>();
        this.blocks = new ArrayList<>();
        this.status = "pending";
    }

    public Task(int id, String subject, String description) {
        this();
        this.id = id;
        this.subject = subject;
        this.description = description;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<Integer> getBlockedBy() {
        return blockedBy;
    }

    public void setBlockedBy(List<Integer> blockedBy) {
        this.blockedBy = blockedBy;
    }

    public List<Integer> getBlocks() {
        return blocks;
    }

    public void setBlocks(List<Integer> blocks) {
        this.blocks = blocks;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getWorktree() {
        return worktree;
    }

    public void setWorktree(String worktree) {
        this.worktree = worktree;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}