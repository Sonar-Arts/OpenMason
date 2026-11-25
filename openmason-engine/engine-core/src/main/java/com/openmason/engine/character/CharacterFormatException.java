package com.openmason.engine.character;

import java.io.IOException;

/** An {@code .omchar} archive could not be read: corrupt, not a character definition, or a newer major version. */
public final class CharacterFormatException extends IOException {

    private final boolean newerVersion;

    public CharacterFormatException(String message, boolean newerVersion) {
        super(message);
        this.newerVersion = newerVersion;
    }

    /** True when the file is a newer major version of the format than this engine reads. */
    public boolean newerVersion() {
        return newerVersion;
    }
}
