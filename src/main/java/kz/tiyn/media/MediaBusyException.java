package kz.tiyn.media;

public class MediaBusyException extends RuntimeException {

    public MediaBusyException() {
        super("image processing is saturated");
    }
}
