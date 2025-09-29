module io.aklivity.zilla.runtime.binding.claimcheck
{
    requires io.aklivity.zilla.runtime.engine;
    requires kotlin.stdlib;
    requires StateMachine;
    requires minio;
    requires jakarta.json;
    requires org.agrona.core;   // <-- FIXED

    exports io.aklivity.zilla.runtime.binding.claimcheck.internal;
    exports io.aklivity.zilla.runtime.binding.claimcheck.internal.config;
    exports io.aklivity.zilla.runtime.binding.claimcheck.internal.stream;

    provides io.aklivity.zilla.runtime.engine.binding.BindingFactorySpi
            with io.aklivity.zilla.runtime.binding.claimcheck.internal.ClaimCheckBindingFactorySpi;

    provides io.aklivity.zilla.runtime.engine.config.OptionsConfigAdapterSpi
            with io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckOptionsConfigAdapter;
}
