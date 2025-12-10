NdMError : Error {

    // Ensure the string argument is passed to Error's constructor
    *new { |string|
        ^super.new(string);
    }

    // Print only a concise, NdM-specific message (no stack trace)
    reportError {
        ("[NdMError] " ++ this.errorString).postln;
    }
}
