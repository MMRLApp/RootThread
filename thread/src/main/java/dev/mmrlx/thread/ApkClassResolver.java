package dev.mmrlx.thread;

import androidx.annotation.RestrictTo;

import com.esotericsoftware.kryo.ClassResolver;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import dalvik.system.PathClassLoader;

@RestrictTo(RestrictTo.Scope.LIBRARY)
class ApkClassResolver implements ClassResolver {
    private static final byte NAME = -1;

    private Kryo kryo;
    private final ClassLoader apkLoader;

    // ID-based registration
    private final Map<Integer, Registration> idToRegistration = new HashMap<>();
    private final IdentityHashMap<Class<?>, Registration> classToRegistration = new IdentityHashMap<>();

    // TODO: Contents of collection 'nameToClass' are updated, but never queried
    // Name-based resolution
    private final Map<String, Class<?>> nameToClass = new HashMap<>();
    private final Map<Integer, Class<?>> nameIdToClass = new HashMap<>();
    private final IdentityHashMap<Class<?>, Integer> classToNameId = new IdentityHashMap<>();

    private int nextNameId = 0;

    // Memoization
    private int memoizedClassId = -1;
    private Registration memoizedClassIdValue;
    private Class<?> memoizedClass;
    private Registration memoizedClassValue;

    public ApkClassResolver(
            String sourceDir,
            String[] splitSourceDirs,
            String nativeLibraryDir,
            ClassLoader parent
    ) {
        StringBuilder path = new StringBuilder(sourceDir);

        if (splitSourceDirs != null) {
            for (String dir : splitSourceDirs) {
                path.append(":").append(dir);
            }
        }

        this.apkLoader = new PathClassLoader(
                path.toString(),
                nativeLibraryDir,
                parent
        );
    }

    @Override
    public void setKryo(Kryo kryo) {
        this.kryo = kryo;
        kryo.setClassLoader(apkLoader);
    }

    @Override
    public Registration register(Registration registration) {
        if (registration == null)
            throw new IllegalArgumentException("registration cannot be null");

        memoizedClass = null;
        memoizedClassId = -1;

        if (registration.getId() != NAME) {
            idToRegistration.put(registration.getId(), registration);
        }

        classToRegistration.put(registration.getType(), registration);
        return registration;
    }

    @Override
    public Registration unregister(int classID) {
        Registration reg = idToRegistration.remove(classID);
        if (reg != null) {
            classToRegistration.remove(reg.getType());
        }
        return reg;
    }

    @Override
    public Registration registerImplicit(Class type) {
        return register(new Registration(type, kryo.getDefaultSerializer(type), NAME));
    }

    @Override
    public Registration getRegistration(Class type) {
        if (type == memoizedClass) return memoizedClassValue;

        Registration reg = classToRegistration.get(type);
        if (reg == null && !kryo.isRegistrationRequired()) {
            reg = registerImplicit(type);
        }

        memoizedClass = type;
        memoizedClassValue = reg;

        return reg;
    }

    @Override
    public Registration getRegistration(int classID) {
        return idToRegistration.get(classID);
    }

    @Override
    public Registration writeClass(Output output, Class type) {
        if (type == null) {
            output.writeByte(Kryo.NULL);
            return null;
        }

        Registration reg = kryo.getRegistration(type);

        if (reg.getId() == NAME) {
            writeName(output, type);
        } else {
            output.writeVarInt(reg.getId() + 2, true);
        }

        return reg;
    }

    // TODO: Raw use of parameterized class 'Class'
    private void writeName(Output output, Class type) {
        output.writeByte(NAME + 2);

        Integer nameId = classToNameId.get(type);
        if (nameId != null) {
            output.writeVarInt(nameId, true);
            return;
        }

        int newId = nextNameId++;
        classToNameId.put(type, newId);

        output.writeVarInt(newId, true);
        output.writeString(type.getName());
    }

    @Override
    public Registration readClass(Input input) {
        int classID = input.readVarInt(true);

        if (classID == Kryo.NULL) return null;

        if (classID == NAME + 2) {
            return readName(input);
        }

        if (classID == memoizedClassId) {
            return memoizedClassIdValue;
        }

        Registration reg = idToRegistration.get(classID - 2);
        if (reg == null) {
            throw new KryoException("Unregistered class ID: " + (classID - 2));
        }

        memoizedClassId = classID;
        memoizedClassIdValue = reg;

        return reg;
    }

    private Registration readName(Input input) {
        int nameId = input.readVarInt(true);

        Class<?> type = nameIdToClass.get(nameId);
        if (type == null) {
            String className = input.readString();

            try {
                type = Class.forName(className, false, apkLoader);
            } catch (ClassNotFoundException e) {
                try {
                    type = Class.forName(className, false, Kryo.class.getClassLoader());
                } catch (ClassNotFoundException ex) {
                    throw new KryoException("Unable to find class: " + className, e);
                }
            }

            nameToClass.put(className, type);
            nameIdToClass.put(nameId, type);
        }

        return kryo.getRegistration(type);
    }

    @Override
    public void reset() {
        if (!kryo.isRegistrationRequired()) {
            classToNameId.clear();
            nameIdToClass.clear();
            nextNameId = 0;
        }
    }
}