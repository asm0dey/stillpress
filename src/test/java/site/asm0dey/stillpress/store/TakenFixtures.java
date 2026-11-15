package site.asm0dey.stillpress.store;

/**
 * The refusal {@code AuthorRepository} throws when a unique index takes a login or an email, for resource unit tests.
 * {@link AuthorRepositoryTest} shows the repository really throws it.
 */
public final class TakenFixtures {
    private TakenFixtures() {
    }

    public static AuthorRepository.Taken email() {
        return new AuthorRepository.Taken(true, new RuntimeException("unique index on email_lower"));
    }

    public static AuthorRepository.Taken login() {
        return new AuthorRepository.Taken(false, new RuntimeException("unique index on login"));
    }
}
