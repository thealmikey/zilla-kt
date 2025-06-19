/*
 * Copyright 2021-2025 Aklivity Inc.
 *
 * Licensed under the Apache License, Version 2.0
 */
module io.aklivity.zilla.runtime.binding.claim
{
    requires io.aklivity.zilla.runtime.engine;

    exports io.aklivity.zilla.runtime.binding.claim.config;

    provides io.aklivity.zilla.runtime.engine.binding.BindingFactorySpi
        with io.aklivity.zilla.runtime.binding.claim.internal.ClaimBindingFactorySpi;

    provides io.aklivity.zilla.runtime.engine.config.OptionsConfigAdapterSpi
        with io.aklivity.zilla.runtime.binding.claim.internal.config.ClaimOptionsConfigAdapter;
}
