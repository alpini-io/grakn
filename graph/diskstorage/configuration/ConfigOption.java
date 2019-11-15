/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2019 Grakn Labs Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package grakn.core.graph.diskstorage.configuration;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import grakn.core.graph.diskstorage.idmanagement.ConflictAvoidanceMode;
import grakn.core.graph.diskstorage.util.time.TimestampProviders;
import grakn.core.graph.graphdb.database.management.ManagementSystem;
import grakn.core.graph.graphdb.database.serialize.StandardSerializer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;


public class ConfigOption<O> extends ConfigElement {

    public enum Type {
        /**
         * Once the database has been opened, these configuration options cannot
         * be changed for the entire life of the database
         */
        FIXED,
        /**
         * These options can only be changed for the entire database cluster at
         * once when all instances are shut down
         */
        GLOBAL_OFFLINE,
        /**
         * These options can only be changed globally across the entire database
         * cluster
         */
        GLOBAL,
        /**
         * These options are global but can be overwritten by a local
         * configuration file
         */
        MASKABLE,
        /**
         * These options can ONLY be provided through a local configuration file
         */
        LOCAL
    }

    private static final Logger LOG = LoggerFactory.getLogger(ConfigOption.class);
    private static final EnumSet<Type> managedTypes = EnumSet.of(Type.FIXED, Type.GLOBAL_OFFLINE, Type.GLOBAL);

    /**
     * This is a subset of types accepted by StandardSerializer.
     * Inclusion is enforced in the static initializer block further down this file.
     */
    private static final Set<Class<?>> ACCEPTED_DATATYPES;

    private static final String ACCEPTED_DATATYPES_STRING;

    static {
        ACCEPTED_DATATYPES = ImmutableSet.of(
                ConflictAvoidanceMode.class,
                Duration.class,
                TimestampProviders.class,
                Instant.class,
                Boolean.class,
                Short.class,
                Integer.class,
                Byte.class,
                Long.class,
                Float.class,
                Double.class,
                String.class,
                String[].class
        );

        StandardSerializer ss = new StandardSerializer();
        for (Class<?> c : ACCEPTED_DATATYPES) {
            if (!ss.validDataType(c)) {
                String msg = String.format("%s datatype %s is not accepted by %s",
                        ConfigOption.class.getSimpleName(), c, StandardSerializer.class.getSimpleName());
                LOG.error(msg);
                throw new IllegalStateException(msg);
            }
        }

        ACCEPTED_DATATYPES_STRING = Joiner.on(", ").join(ACCEPTED_DATATYPES);
    }

    private final Type type;
    private final Class<O> datatype;
    private final O defaultValue;
    private final Predicate<O> verificationFct;
    private boolean isHidden = false;
    private ConfigOption<?> supersededBy;

    public ConfigOption(ConfigNamespace parent, String name, String description, Type type, O defaultValue) {
        this(parent, name, description, type, defaultValue, disallowEmpty((Class<O>) defaultValue.getClass()));
    }

    public ConfigOption(ConfigNamespace parent, String name, String description, Type type, O defaultValue, Predicate<O> verificationFct) {
        this(parent, name, description, type, (Class<O>) defaultValue.getClass(), defaultValue, verificationFct);
    }

    public ConfigOption(ConfigNamespace parent, String name, String description, Type type, Class<O> dataType) {
        this(parent, name, description, type, dataType, disallowEmpty(dataType));
    }

    public ConfigOption(ConfigNamespace parent, String name, String description, Type type, Class<O> dataType, Predicate<O> verificationFct) {
        this(parent, name, description, type, dataType, null, verificationFct);
    }

    public ConfigOption(ConfigNamespace parent, String name, String description, Type type, Class<O> dataType, O defaultValue) {
        this(parent, name, description, type, dataType, defaultValue, disallowEmpty(dataType));
    }

    public ConfigOption(ConfigNamespace parent, String name, String description, Type type, Class<O> dataType, O defaultValue, Predicate<O> verificationFct) {
        this(parent, name, description, type, dataType, defaultValue, verificationFct, null);
    }

    public ConfigOption(ConfigNamespace parent, String name, String description, Type type, Class<O> dataType, O defaultValue, Predicate<O> verificationFct, ConfigOption<?> supersededBy) {
        super(parent, name, description);
        Preconditions.checkNotNull(type);
        Preconditions.checkNotNull(dataType);
        Preconditions.checkNotNull(verificationFct);
        this.type = type;
        this.datatype = dataType;
        this.defaultValue = defaultValue;
        this.verificationFct = verificationFct;
        this.supersededBy = supersededBy;
        // A static initializer calls this constructor, so LOG before throwing the IAE
        if (!ACCEPTED_DATATYPES.contains(dataType)) {
            String msg = String.format("Datatype %s is not one of %s", dataType, ACCEPTED_DATATYPES_STRING);
            LOG.error(msg);
            throw new IllegalArgumentException(msg);
        }
    }

    public ConfigOption<O> hide() {
        this.isHidden = true;
        return this;
    }

    public boolean isHidden() {
        return isHidden;
    }

    public Type getType() {
        return type;
    }

    public Class<O> getDatatype() {
        return datatype;
    }

    public O getDefaultValue() {
        return defaultValue;
    }

    public boolean isFixed() {
        return type == Type.FIXED;
    }

    public boolean isGlobal() {
        return type == Type.FIXED || type == Type.GLOBAL_OFFLINE || type == Type.GLOBAL || type == Type.MASKABLE;
    }

    /**
     * Returns true on config options whose values are not local or maskable, that is,
     * cluster-wide options that are either fixed or which can be changed only by using
     * the {@link ManagementSystem}
     * (and not by editing the local config).
     *
     * @return true for managed options, false otherwise
     */
    public boolean isManaged() {
        return managedTypes.contains(type);
    }

    public static EnumSet<Type> getManagedTypes() {
        return EnumSet.copyOf(managedTypes);
    }

    public boolean isLocal() {
        return type == Type.MASKABLE || type == Type.LOCAL;
    }

    public boolean isDeprecated() {
        return null != supersededBy;
    }

    public ConfigOption<?> getDeprecationReplacement() {
        return supersededBy;
    }

    @Override
    public boolean isOption() {
        return true;
    }

    public O get(Object input) {
        if (input == null) {
            input = defaultValue;
        }
        if (input == null) {
            Preconditions.checkState(verificationFct.apply((O) input), "Need to set configuration value: %s", this.toString());
            return null;
        } else {
            return verify(input);
        }
    }

    public O verify(Object input) {
        Preconditions.checkNotNull(input);
        Preconditions.checkArgument(datatype.isInstance(input), "Invalid class for configuration value [%s]. Expected [%s] but given [%s]", this.toString(), datatype, input.getClass());
        O result = (O) input;
        Preconditions.checkArgument(verificationFct.apply(result), "Invalid configuration value for [%s]: %s", this.toString(), input);
        return result;
    }


    //########### HELPER METHODS ##################

    public static <E extends Enum> E getEnumValue(String str, Class<E> enumClass) {
        final String trimmed = str.trim();
        if (StringUtils.isBlank(trimmed)) {
            return null;
        }
        return Arrays.stream(enumClass.getEnumConstants())
                .filter(e -> e.toString().equalsIgnoreCase(trimmed))
                .findAny()
                .orElseThrow(() -> new IllegalArgumentException("Invalid enum string provided for [" + enumClass + "]: " + trimmed));
    }

    public static <O> Predicate<O> disallowEmpty(Class<O> clazz) {
        return o -> {
            if (o == null) {
                return false;
            }
            if (o instanceof String) {
                return StringUtils.isNotBlank((String) o);
            }
            return (!o.getClass().isArray() || (Array.getLength(o) != 0 && Array.get(o, 0) != null))
                    && (!(o instanceof Collection) || (!((Collection) o).isEmpty() && ((Collection) o).iterator().next() != null));
        };
    }

    public static Predicate<Integer> positiveInt() {
        return num -> num != null && num > 0;
    }

    public static Predicate<Integer> nonnegativeInt() {
        return num -> num != null && num >= 0;
    }

    public static Predicate<Long> positiveLong() {
        return num -> num != null && num > 0;
    }


}