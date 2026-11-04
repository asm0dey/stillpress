package site.asm0dey.stillpress.admin;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
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
    @Inject
    PublishStatus status;
    @Inject
    PublishQueue queue;
    @Inject
    Jobs jobs;

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
