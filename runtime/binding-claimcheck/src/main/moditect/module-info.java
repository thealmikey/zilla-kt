module io.aklivity.zilla.runtime.binding.claimcheck

{
    requires kotlin.stdlib;
    requires jakarta.json;
    requires org.agrona.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.yaml;
    requires io.aklivity.zilla.runtime.engine;
    requires org.leadpony.justify;

    exports io.aklivity.zilla.runtime.binding.claimcheck.config;
    exports io.aklivity.zilla.runtime.binding.claimcheck.internal;
    exports io.aklivity.zilla.runtime.binding.claimcheck.internal.config;
    exports io.aklivity.zilla.runtime.binding.claimcheck.internal.stream;

    provides io.aklivity.zilla.runtime.engine.binding.BindingFactorySpi
            with io.aklivity.zilla.runtime.binding.claimcheck.internal.ClaimCheckBindingFactorySpi;

    provides io.aklivity.zilla.runtime.engine.config.OptionsConfigAdapterSpi
            with io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckOptionsConfigAdapter;

    provides io.aklivity.zilla.runtime.engine.config.ConditionConfigAdapterSpi
            with io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckConditionConfigAdapter;

    provides io.aklivity.zilla.runtime.engine.config.WithConfigAdapterSpi
            with io.aklivity.zilla.runtime.binding.claimcheck.config.ClaimCheckWithConfigAdapter;
}
