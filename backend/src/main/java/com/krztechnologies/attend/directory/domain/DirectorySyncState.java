package com.krztechnologies.attend.directory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Single row: the Graph users/delta link for the next incremental sync and the time of the last full sync. */
@Entity
@Table(name = "directory_sync_state")
public class DirectorySyncState {

    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Short id;

    @Column(name = "delta_link")
    private String deltaLink;

    @Column(name = "last_full_sync_at")
    private Instant lastFullSyncAt;

    protected DirectorySyncState() {}

    public String getDeltaLink() {
        return deltaLink;
    }

    public Instant getLastFullSyncAt() {
        return lastFullSyncAt;
    }

    public void completed(String newDeltaLink, boolean full, Instant at) {
        this.deltaLink = newDeltaLink;
        if (full) {
            this.lastFullSyncAt = at;
        }
    }
}
