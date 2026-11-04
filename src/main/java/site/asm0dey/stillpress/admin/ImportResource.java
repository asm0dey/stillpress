package site.asm0dey.stillpress.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import module java.base;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import site.asm0dey.stillpress.importer.AlreadyImportedException;
import site.asm0dey.stillpress.importer.ImportJob;
import site.asm0dey.stillpress.importer.ImportOptions;

/**
 * Uploads a WXR export and imports it in the background; the client polls {@code GET} for progress.
 */
@Path("/admin/api/import")
@RolesAllowed("administrator")
public class ImportResource {
    @Inject
    ImportJob job;

    @GET
    public ImportJob.Status status() {
        return job.status();
    }

    @POST
    public Response upload(
            @RestForm FileUpload file,
            @RestForm @DefaultValue("10") int postsPerPage,
            @RestForm @DefaultValue("10") int postsPerRss,
            @RestForm @DefaultValue("category") String categoryBase,
            @RestForm @DefaultValue("tag") String tagBase
    ) {
        if (file == null) {
            return Response.status(400).entity(Map.of("error", "file is required")).build();
        }
        ImportOptions options;
        try {
            options = new ImportOptions(postsPerPage, categoryBase, tagBase, postsPerRss);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        }
        try {
            job.start(file.uploadedFile(), options);
        } catch (AlreadyImportedException e) {
            return Response
                .status(409)
                .entity(Map.of("error", "already_imported", "message", e.getMessage()))
                .build();
        }
        return Response.status(202).entity(job.status()).build();
    }
}
