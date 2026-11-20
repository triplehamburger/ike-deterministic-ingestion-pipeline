package network.ike.docs.fhirfragments;

/** A usage or configuration error to report to the user without a stack trace. */
final class CliException extends RuntimeException {
    CliException(String message) {
        super(message);
    }
}
