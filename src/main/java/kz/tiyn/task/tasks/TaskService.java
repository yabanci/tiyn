package kz.tiyn.task.tasks;

import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import kz.tiyn.identity.FamilyDirectory;
import kz.tiyn.media.InvalidMediaException;
import kz.tiyn.media.MediaBusyException;
import kz.tiyn.media.Storage;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.platform.security.Role;
import kz.tiyn.platform.tx.ConflictRetry;
import kz.tiyn.task.tasks.dto.CreateTaskRequest;
import kz.tiyn.task.tasks.dto.ProofResponse;
import kz.tiyn.task.tasks.error.TaskError;
import kz.tiyn.task.tasks.error.TaskException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class TaskService {

    private final TaskStore tasks;
    private final IdempotencyStore keys;
    private final FamilyDirectory family;
    private final Storage storage;
    private final TransactionTemplate tx;
    private final TaskProperties properties;
    private final Clock clock;

    TaskService(
            TaskStore tasks,
            IdempotencyStore keys,
            FamilyDirectory family,
            Storage storage,
            TransactionTemplate tx,
            TaskProperties properties,
            Clock clock) {
        this.tasks = tasks;
        this.keys = keys;
        this.family = family;
        this.storage = storage;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    Task create(Principal caller, CreateTaskRequest request) {
        requireRole(caller, Role.PARENT);
        if (request.teenId() == null) {
            throw new TaskException(TaskError.TEEN_REQUIRED);
        }
        requireLinked(caller.userId(), request.teenId());
        TaskKind kind = parseKind(request.kind());
        var draft = new NewTask(
                caller.userId(),
                request.teenId(),
                kind,
                request.title(),
                request.description(),
                request.rewardAmount(),
                request.penaltyAmount(),
                request.currency(),
                request.deadlineAt());
        return tx.execute(status -> {
            Task task = request.milestoneId() == null
                    ? Task.assign(UUID.randomUUID(), draft, clock.instant(), properties.maxRewardAmount())
                    : Task.assignInMilestone(
                            UUID.randomUUID(),
                            tasks.findLocked(request.milestoneId())
                                    .orElseThrow(() -> new TaskException(TaskError.INVALID_MILESTONE)),
                            draft,
                            clock.instant(),
                            properties.maxRewardAmount());
            tasks.insert(task);
            return task;
        });
    }

    Task get(Principal caller, UUID id) {
        return authorised(caller, id);
    }

    List<Task> list(Principal caller, UUID teenId, TaskState state) {
        if (caller.role() == Role.TEEN) {
            return tasks.list(null, caller.userId(), state);
        }
        Map<UUID, Boolean> linked = new HashMap<>();
        return tasks.list(caller.userId(), teenId, state).stream()
                .filter(t -> linked.computeIfAbsent(t.teenId(), id -> family.areLinked(caller.userId(), id)))
                .toList();
    }

    Task submitText(Principal caller, UUID id, String idempotencyKey, String text) {
        requireKey(idempotencyKey);
        return submit(caller, id, idempotencyKey, null, text);
    }

    Task submitImage(Principal caller, UUID id, String idempotencyKey, String contentType, InputStream body) {
        requireKey(idempotencyKey);
        requireRole(caller, Role.TEEN);
        Task current = authorised(caller, id);
        String objectKey = current.acceptsProofAt(clock.instant()) ? upload(id, contentType, body) : null;
        return submit(caller, current.id(), idempotencyKey, objectKey, null);
    }

    Task approve(Principal caller, UUID id) {
        return decide(caller, id, (t, now) -> t.approve(caller.userId(), now));
    }

    Task reject(Principal caller, UUID id, String note) {
        return decide(caller, id, (t, now) -> t.reject(caller.userId(), note, now));
    }

    Task requestRedo(Principal caller, UUID id, String note, Instant newDeadlineAt) {
        return decide(caller, id, (t, now) -> {
            lockMilestoneRow(t);
            t.requestRedo(caller.userId(), note, newDeadlineAt, boundsFor(t), now);
        });
    }

    Task cancel(Principal caller, UUID id) {
        requireRole(caller, Role.PARENT);
        return retry(() -> tx.execute(status -> {
            Task task = authorised(caller, id);
            Instant now = clock.instant();
            if (task.kind() == TaskKind.MILESTONE) {
                task = tasks.findLocked(id).orElseThrow(() -> new TaskException(TaskError.NOT_FOUND));
                task.cancel(caller.userId(), now);
                tasks.save(task);
                for (Task child : tasks.findOpenInMilestone(id)) {
                    child.cancel(caller.userId(), now);
                    tasks.save(child);
                }
                return task;
            }
            task.cancel(caller.userId(), now);
            tasks.save(task);
            return task;
        }));
    }

    void cancelOpenTasksAfterUnlink(UUID parentId, UUID teenId) {
        tx.executeWithoutResult(status -> {
            if (family.areLinked(parentId, teenId)) {
                return;
            }
            Instant now = clock.instant();
            for (Task task : tasks.findOpenFor(parentId, teenId)) {
                task.cancel(parentId, now);
                tasks.save(task);
            }
        });
    }

    boolean expire(UUID id, Instant now) {
        return Boolean.TRUE.equals(tx.execute(status -> tasks.find(id)
                .map(task -> {
                    task.expire(now);
                    return tasks.save(task);
                })
                .orElse(false)));
    }

    MilestoneProgress progress(Task milestone) {
        return tasks.progress(milestone.id());
    }

    List<ProofResponse> proofs(Principal caller, UUID id) {
        requireRole(caller, Role.PARENT);
        Task task = authorised(caller, id);
        return task.proofs().stream()
                .map(p -> new ProofResponse(
                        p.attemptNo(),
                        p.objectKey() == null ? null : view(p.objectKey()).toString(),
                        p.textBody(),
                        p.createdAt()))
                .toList();
    }

    private String upload(UUID taskId, String contentType, InputStream body) {
        try {
            return storage.put(taskId.toString(), contentType, body);
        } catch (InvalidMediaException e) {
            throw new TaskException(TaskError.UNSUPPORTED_PROOF);
        } catch (MediaBusyException e) {
            throw new TaskException(TaskError.MEDIA_BUSY);
        }
    }

    private Task submit(Principal caller, UUID id, String idempotencyKey, String objectKey, String text) {
        requireRole(caller, Role.TEEN);
        return retry(() -> tx.execute(status -> {
            Task task = authorised(caller, id);
            if (!keys.claim(id + ":" + task.currentAttempt() + ":" + idempotencyKey)) {
                return authorised(caller, id);
            }
            task.submit(caller.userId(), UUID.randomUUID(), objectKey, text, clock.instant());
            tasks.save(task);
            return task;
        }));
    }

    private void lockMilestoneRow(Task task) {
        UUID milestoneId = task.kind() == TaskKind.MILESTONE ? task.id() : task.milestoneId();
        if (milestoneId != null) {
            tasks.lock(milestoneId);
        }
    }

    private DeadlineBounds boundsFor(Task task) {
        Instant earliest = task.kind() == TaskKind.MILESTONE ? tasks.latestOpenChildDeadline(task.id()) : null;
        return new DeadlineBounds(earliest, milestoneDeadline(task));
    }

    private Instant milestoneDeadline(Task task) {
        if (task.milestoneId() == null) {
            return null;
        }
        return tasks.find(task.milestoneId()).map(Task::deadlineAt).orElse(null);
    }

    private Task decide(Principal caller, UUID id, Decision decision) {
        requireRole(caller, Role.PARENT);
        return retry(() -> tx.execute(status -> {
            Task task = authorised(caller, id);
            decision.apply(task, clock.instant());
            tasks.save(task);
            return task;
        }));
    }

    private Task authorised(Principal caller, UUID id) {
        Task task = tasks.find(id).orElseThrow(() -> new TaskException(TaskError.NOT_FOUND));
        if (caller.role() == Role.TEEN) {
            if (!task.teenId().equals(caller.userId())) {
                throw new TaskException(TaskError.FORBIDDEN);
            }
        } else if (!task.parentId().equals(caller.userId()) || !family.areLinked(task.parentId(), task.teenId())) {
            throw new TaskException(TaskError.FORBIDDEN);
        }
        return task;
    }

    private void requireLinked(UUID parentId, UUID teenId) {
        if (!family.areLinked(parentId, teenId)) {
            throw new TaskException(TaskError.FORBIDDEN);
        }
    }

    private static void requireRole(Principal caller, Role role) {
        if (caller.role() != role) {
            throw new TaskException(TaskError.FORBIDDEN);
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new TaskException(TaskError.INVALID_IDEMPOTENCY_KEY);
        }
    }

    private static TaskKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return TaskKind.TASK;
        }
        try {
            return TaskKind.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new TaskException(TaskError.INVALID_KIND);
        }
    }

    private URI view(String objectKey) {
        return storage.presignView(objectKey);
    }

    private static Task retry(Supplier<Task> work) {
        return ConflictRetry.run(work, () -> new TaskException(TaskError.CONCURRENT_MODIFICATION));
    }

    @FunctionalInterface
    private interface Decision {
        void apply(Task task, Instant now);
    }
}
