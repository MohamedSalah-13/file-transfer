package com.hamza.filetransfer.client;

/** Carries an {@link AppError} so the UI can show it translated. */
public class AppException extends Exception {

    private final AppError error;

    public AppException(AppError error) {
        super(error.code() + (error.args().isEmpty() ? "" : " " + error.args()));
        this.error = error;
    }

    public AppException(String code, String... args) {
        this(new AppError(code, args));
    }

    public AppError error() {
        return error;
    }
}
