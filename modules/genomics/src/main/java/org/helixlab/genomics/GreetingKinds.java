/*
 * Copyright (c) the publisher of genomics. All rights reserved.
 */
package org.helixlab.genomics;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.kernel.api.data.KindProvider;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** The kind this module computes, {@code genomics-greeting}: one greeting per name, served by the kernel like any kind. */
public class GreetingKinds implements KindProvider {

    static final String KIND = "genomics-greeting";
    private final List<String> names = List.of("ASHA", "RAVI");

    private final Greeting greeting;

    public GreetingKinds(Greeting greeting) {
        this.greeting = greeting;
    }

    @Override
    public String name() {
        return "genomics-greetings";
    }

    @Override
    public Set<String> kinds() {
        return Set.of(KIND);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
        return KIND.equals(ref.kind()) ? Optional.of(document(ref.id())) : Optional.empty();
    }

    @Override
    public boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink) {
        if (!KIND.equals(kind)) {
            return false;
        }
        names.forEach(n -> sink.accept(document(n)));
        return true;
    }

    private EntityDocument document(String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("NAME", name);
        row.put("TEXT", greeting.answer(name));
        return new EntityDocument(new EntityRef(KIND, name), DataNode.of(row), new Provenance(name(), 1, Instant.now(), false, null));
    }
}
