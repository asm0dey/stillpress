package site.asm0dey.stillpress.admin;

import jakarta.ws.rs.core.Response;
import module java.base;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import site.asm0dey.stillpress.importer.ImportRunningException;

/**
 * Global: every admin write that calls {@code ImportGuard.close()} answers 409 while an import runs.
 */
public class ImportRunningMapper {
    @ServerExceptionMapper
    public Response importRunning(ImportRunningException e) {
        return Response.status(409).entity(Map.of("error", "import_running")).build();
    }
}
