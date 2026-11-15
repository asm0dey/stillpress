package site.asm0dey.stillpress.admin;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.publish.PublishStatus;

/**
 * The publisher's state for the dashboard, and the administrator's full republish.
 */
@Path("/admin/api/publish")
public class PublishResource {
    private final PublishStatus status;
    private final PublishQueue queue;
    private final Jobs jobs;

    PublishResource(PublishStatus status, PublishQueue queue, Jobs jobs) {
        this.status = status;
        this.queue = queue;
        this.jobs = jobs;
    }

    @GET
    @Authenticated
    public PublishStatus.State state() {
        return status.current();
    }

    @POST
    @RolesAllowed("administrator")
    @Transactional
    public Response republish() {
        queue.all();
        jobs.requestPublish();
        return Response.accepted(status.current()).build();
    }
}
