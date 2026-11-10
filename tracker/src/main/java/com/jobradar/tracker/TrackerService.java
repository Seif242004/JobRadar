package com.jobradar.tracker;

import com.jobradar.tracker.internal.TrackerRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** Reads and updates a user's applications. Every method is scoped by {@code userId}. */
@Service
public class TrackerService {

    private final TrackerRepository repository;

    TrackerService(TrackerRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the status of one of the user's applications.
     *
     * @return false when the application does not exist, belongs to another user, or has not
     *         been sent yet
     */
    public boolean updateStatus(long userId, long applicationId, ApplicationStatus status) {
        return repository.updateStatus(userId, applicationId, status);
    }

    /** The user's applications, newest first, excluding emails that are still being sent. */
    public List<TrackedApplication> recent(long userId, int limit) {
        return repository.recent(userId, limit);
    }
}
