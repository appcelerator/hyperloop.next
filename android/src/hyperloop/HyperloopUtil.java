/**
 * Appcelerator Titanium Mobile
 * Copyright (c) 2015 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */

package hyperloop;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.appcelerator.kroll.KrollDict;
import org.appcelerator.kroll.KrollProxy;
import org.appcelerator.titanium.proxy.ActivityProxy;
import org.appcelerator.titanium.proxy.IntentProxy;
import org.appcelerator.titanium.proxy.TiViewProxy;

abstract class HyperloopUtil {
    // TODO This is a hack. We should move all this stuff into the BaseProxy or
    // something...

    static final String TAG = "HyperloopUtil";

    // Don't allow creating an instance
    private HyperloopUtil() {
    }

    /**
     * Wrap native objects with hyperloop proxies.
     *
     * @param args
     * @return
     */
    static Object[] wrapArguments(Class<?>[] params, Object[] args) {
        final int argCount = (args == null) ? 0 : args.length;
        if (argCount == 0) {
            // We cannot pass along a null reference for argument array to
            // native V8Function, or it will crash!
            // return empty array
            return new Object[0];
        }
        if (argCount > params.length) {
            // VARARGS!
        }
        Object[] wrapped = new Object[argCount];
        for (int i = 0; i < argCount; i++) {
            // FIXME Handle varargs! We need to make sure we only go to second
            // last param then, and ensure all remaining args
            // are of the component type of the last param
            wrapped[i] = wrap(params[i], args[i]);
        }
        return wrapped;
    }

    /**
     * Wraps a return value in a proxy if necessary. if it's already a proxy or
     * primitive, the framework will convert to JS for us.
     *
     * @param result
     * @return
     */
    static Object wrap(Class<?> paramType, Object result) {
        if (result == null) {
            return result;
        }
        if (result instanceof byte[]) { // our bridge can't handle byte[], but can do short[] - so convert to short[]
            return convertTo(result, short[].class);
        } else if (result instanceof Byte) { // our bridge can't handle byte, but can do short - so convert to short
            return convertTo(result, short.class);
        } else if (result instanceof char[]) {
            // convert to String, so we end up with JS String
            return new String((char[]) result);
        } else if (result instanceof Character) {
            // convert to String, so we end up with JS String
            return ((Character) result).toString();
        }
        return isKnownType(result) ? result
                : HyperloopModule.getProxyFactory().newInstance(paramType, result);
    }

    /**
     * Is this item a type that the JS engine can handle/convert on it's own? if
     * so, we don't need to worry about converting it by wrapping with a proxy.
     * Also we've whitelisted that it's ok to return.
     *
     * @param item
     * @return
     */
    private static boolean isKnownType(Object item) {
        // Here's what TypeConverter lists:
        // short, int, long, float, double, boolean, string, Date, (Object as Function?)
        // Object[], boolean[], short[], int[], long[], float[], double[]
        // Since we almost always end up here due to reflection, we always boxed types, not primitives in those cases
        // so we check against the boxed types, not primitives, first (including arrays: for example, Integer[] instanceof Object[] == true)
        return item instanceof KrollProxy || item instanceof Integer
                || item instanceof Double || item instanceof Float
                || item instanceof Byte || item instanceof Short
                || item instanceof Long || item instanceof HashMap
                || item instanceof String || item instanceof Boolean
                || item instanceof Date || item instanceof Object[]
                // When we get a field through reflection we _can_ get primitive arrays, so check for all of those too
                // Note lack of byte[], since bridge doesn't handle that, we treat it specially in wrap
                || item instanceof int[] || item instanceof double[]
                || item instanceof float[] || item instanceof short[]
                || item instanceof long[] || item instanceof boolean[];
    }

    /**
     * Validate whether or not the current device is a simulator.
     *
     * @return
     */
    public static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk".equals(Build.PRODUCT);
    }

    /**
     * Convert the "raw" args we received to unwrap proxies down to the object
     * they hold.
     *
     * @param arguments
     * @return
     */
    static Object[] unwrapArguments(Object[] arguments) {
        final int argCount = (arguments == null) ? 0 : arguments.length;
        Object[] unwrapped = new Object[argCount];
        for (int i = 0; i < argCount; i++) {
            unwrapped[i] = unwrap(arguments[i]);
        }
        return unwrapped;
    }

    /**
     * If the argument is a proxy, unwrap the native object it holds.
     *
     * @param object
     * @return
     */
    static Object unwrap(Object object) {
        if (object == null) {
            return null;
        }

        // Native code handles unwrapping JS proxy to the underlying Java
        // BaseProxy
        // see
        // https://github.com/appcelerator/titanium_mobile/blob/master/android/runtime/v8/src/native/TypeConverter.cpp#L628

        // If it's a proxy, unwrap the native object we're wrapping
        if (object instanceof BaseProxy) {
            return ((BaseProxy) object).getWrappedObject();
        }

        // Convert some of the titanium wrappers
        if (object instanceof ActivityProxy) {
            ActivityProxy ap = (ActivityProxy) object;
            return ap.getActivity();
        }
        if (object instanceof IntentProxy) {
            IntentProxy ip = (IntentProxy) object;
            Intent i = ip.getIntent();
            // Because our SDK is lame, if you supply not creation dictionary, the wrapped Intent object may be null!
            if (i != null) {
                return i;
            }
            ip.handleCreationDict(new KrollDict());
            return ip.getIntent();
        }
        // Convert Ti.UI.View subclasses
        if (object instanceof TiViewProxy) {
            TiViewProxy tvp = (TiViewProxy) object;
            return tvp.getOrCreateView().getNativeView();
        }
        // TODO Convert more Titanium types!
        return object;
    }

    /**
     * Converts the raw Object[] we receive for a method call into the required
     * types that the method takes, and handles varargs. See
     * {@link #convertTo(Object, Class)}
     *
     * @param arguments
     * @param parameterTypes
     * @param isVarArgs
     * @return
     */
    static Object[] convert(Object[] arguments, Class<?>[] parameterTypes,
            boolean isVarArgs) {
        if (arguments == null) {
            return null;
        }
        int paramCount = parameterTypes.length;
        if (paramCount == 0) {
            return new Object[0];
        }

        int end = paramCount;
        if (isVarArgs) {
            end = paramCount - 1;
        }
        Object[] result = new Object[paramCount];
        for (int i = 0; i < end; i++) {
            result[i] = convertTo(arguments[i], parameterTypes[i]);
        }
        if (isVarArgs) {
            // Generate an array of the given type from all the remaining
            // arguments
            int argCount = arguments.length;
            int size = argCount - end;
            Class<?> componentType = parameterTypes[end].getComponentType();
            Object varargs = Array.newInstance(componentType, size);
            for (int x = end; x < argCount; x++) {
                Array.set(varargs, x - end, convertTo(arguments[x], componentType));
            }
            result[end] = varargs;
        }
        return result;
    }

    /**
     * This is effectively to fix downcasting for primitives. We always get
     * doubles from JS Number, so we need to handle allowing more broad input
     * number types and "casting" them to the field/param type required.
     *
     * @param newValue
     * @param target
     * @return
     */
    static Object convertTo(Object newValue, Class<?> target) {
        if (newValue == null) {
            return null; // really bad if the target is a primitive type!
        }
        // are we of an assignable type already? Then just use what we have
        if (target.isAssignableFrom(newValue.getClass())) {
            return newValue;
        }

        if (target.isPrimitive()) {
            if (newValue instanceof Number) {
                Number num = (Number) newValue;
                if (byte.class.equals(target)) {
                    return num.byteValue();
                } else if (int.class.equals(target)) {
                    return num.intValue();
                } else if (double.class.equals(target)) {
                    return num.doubleValue();
                } else if (float.class.equals(target)) {
                    return num.floatValue();
                } else if (short.class.equals(target)) {
                    return num.shortValue();
                } else if (long.class.equals(target)) {
                    return num.longValue();
                } else if (char.class.equals(target)) {
                    if (num instanceof Float || num instanceof Double) {
                        Log.e(TAG, "Supplied a non-integer number value for char primitive: " + num + ". Will default to (char) 0.");
                        return Character.valueOf((char) 0);
                    }
                    int asInt = num.intValue();
                    if (asInt >= 0 && asInt <= Character.MAX_VALUE) {
                        return Character.valueOf((char) num.intValue());
                    }
                    Log.e(TAG, "Supplied an integer value out of range for char primitive: " + asInt + ". Will default to (char) 0.");
                    return Character.valueOf((char) 0);
                }
            } else if (newValue instanceof String) {
                String string = (String) newValue;
                if (char.class.equals(target)) {
                    if (string.length() == 0) {
                        Log.e(TAG, "Supplied an empty string for char. Will default to (char) 0.");
                        return Character.valueOf((char) 0);
                    }
                    if (string.length() > 1) {
                        Log.e(TAG, "Supplied a string with more than one character for char. Will default to first character.");
                    }
                    return Character.valueOf(string.charAt(0));
                } else if (char[].class.equals(target)) {
                    return string.toCharArray();
                }
            }
            // Probably a big no-no...
            return newValue;
        } else if (target.isArray()) {
            // treat string -> char[] special
            if (newValue instanceof String && char[].class.equals(target)) {
                return ((String) newValue).toCharArray();
            }
            // TODO Allow new value to be List/Collection too, see #distance
            // Handle arrays
            if (newValue.getClass().isArray()) {
                Class<?> component = target.getComponentType();
                int length = Array.getLength(newValue);
                Object converted = Array.newInstance(component, length);
                for (int i = 0; i < length; i++) {
                    Array.set(converted, i, convertTo(Array.get(newValue, i), component));
                }
                return converted;
            }
        }

        // Special case proxy conversions
        if (IntentProxy.class.equals(target) && (newValue instanceof Intent)) {
            return new IntentProxy((Intent) newValue);
        } else if (ActivityProxy.class.equals(target) && (newValue instanceof Activity)) {
            return new ActivityProxy((Activity) newValue);
        }
        // Not a primitive or array, or special proxy conversion... So, just hope it's the right type?
        return newValue;
    }

    /**
     * Given a class, method name and some arguments - can we find the intended
     * target method to call?
     *
     * @param c
     * @param name
     * @param arguments
     * @param instanceMethod
     * @return
     */
    static Method resolveMethod(Class<?> c, String name, Object[] arguments,
            boolean instanceMethod) {
        int argCount = (arguments == null) ? 0 : arguments.length;
        // if no args, assume we want a no-arg constructor!
        if (argCount == 0) {
            try {
                return c.getMethod(name);
            } catch (NoSuchMethodException e) {
                // may be no method with this name and no args (bad method name,
                // or maybe takes varargs)
            }
        }

        // TODO Is there a more performant way to search methods? This can
        // result in a lot of methods for some types
        Method[] methods = c.getMethods();
        // TODO Filter by instance/static first?
        if (methods.length == 1) {
            return methods[0];
        }

        List<Match<Method>> matches = new ArrayList<Match<Method>>();
        for (Method method : methods) {
            if (!method.getName().equals(name)) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            boolean isVarArgs = method.isVarArgs();
            Match<Method> match = null;
            if (isVarArgs) {
                if (argCount >= (params.length - 1)) {
                    match = createMatch(method, params, arguments, isVarArgs);
                }
            } else if (params.length == argCount) {
                match = createMatch(method, params, arguments, isVarArgs);
            }
            if (match != null) {
                // Shortcut if the distance is 0: That's an exact match...
                if (match.isExact()) {
                    return match.method;
                }
                matches.add(match);
            }
        }
        if (matches.isEmpty()) {
            // Log something?
            return null;
        }
        // Sort matches by distance (lowest wins)
        Collections.sort(matches);
        return matches.get(0).method;
    }

    /**
     * Given an argument array and a class we want to instantiate, resolve the
     * best matching constructor.
     *
     * @param c
     * @param arguments
     * @return
     */
    static Constructor resolveConstructor(Class<?> c, Object[] arguments) {
        int argCount = (arguments == null) ? 0 : arguments.length;
        // if no args, assume we want a no-arg constructor!
        if (argCount == 0) {
            try {
                return c.getConstructor();
            } catch (NoSuchMethodException e) {
                // TODO may be no no-arg constructor!
                e.printStackTrace();
            }
        }

        Constructor<?>[] constructors = c.getConstructors();
        if (constructors.length == 1) {
            return constructors[0];
        }

        List<Match<Constructor>> matches = new ArrayList<Match<Constructor>>();
        for (Constructor constructor : constructors) {
            Class<?>[] params = constructor.getParameterTypes();
            boolean isVarArgs = constructor.isVarArgs();
            Match<Constructor> match = null;
            if (isVarArgs) {
                if (argCount >= (params.length - 1)) {
                    match = createMatch(constructor, params, arguments, isVarArgs);
                }
            } else if (params.length == argCount) {
                match = createMatch(constructor, params, arguments, isVarArgs);
            }
            if (match != null) {
                // Shortcut if the distance is 0: That's an exact match...
                if (match.isExact()) {
                    return match.method;
                }
                matches.add(match);
            }
        }
        if (matches.isEmpty()) {
            // Log something?
            return null;
        }
        // Sort matches by distance (lowest wins)
        Collections.sort(matches);
        return matches.get(0).method;
    }

    /**
     * Determines if the method is a match. If not, this will return null. If it
     * is, returns a Match object holding the method and the distance of the
     * match.
     *
     * @param m
     * @param params
     * @param arguments
     * @return
     */
    private static <T> Match<T> createMatch(T m, Class<?>[] params, Object[] arguments,
            boolean isVarArgs) {
        int distance = Match.EXACT; // start as exact, increasing as we get
                                    // further
        // match all arguments normally
        int end = params.length;
        // for varargs match to last param type normally.
        if (isVarArgs) {
            end = params.length - 1;
        }

        // make sure a given arg matches
        for (int i = 0; i < end; i++) {
            int argDistance = matchArg(params[i], arguments[i]);
            if (argDistance >= 0) {
                distance += argDistance;
            } else {
                // can't convert, no match
                return null;
            }
        }

        if (isVarArgs) {
            // Need to do special matching for last param
            int start = params.length - 1;
            Class<?> lastParam = params[start];
            Class<?> componentType = lastParam.getComponentType();
            // Now match that all the rest of the args can be of this type!
            for (int i = start; i < arguments.length; i++) {
                int argDistance = matchArg(componentType, arguments[i]);
                if (argDistance >= 0) {
                    distance += argDistance;
                } else {
                    // can't convert, no match
                    return null;
                }
            }
        }

        return new Match<T>(m, distance);
    }

    private static int matchArg(Class<?> param, Object arg) {
        if (arg == null) {
            // can't have a null primitive arg, no match
            if (param.isPrimitive()) {
                return -1;
            }
            // if null arg for a non-primitive, assume no distance change
            return 0;
        }
        // typical case
        return distance(param, arg.getClass(), arg);
    }

    /**
     * Determine the distance between the argument types and the intended
     * parameter types. Returns -1 if no match. Note that this and {@link #convertTo(Object, Class)} basically need to stay in sync
     *
     * @param target The target type we're trying to match against!
     * @param argument The type of the argument (arg.getClass())
     * @param arg The actual argument we received
     * @return
     */
    private static int distance(Class<?> target, Class<?> argument, Object arg) {
        // Primitives - we always have a boxed type for our argument
        if (target.isPrimitive()) {
            // https://docs.oracle.com/javase/specs/jls/se7/html/jls-5.html#jls-5.3
            // Says we can do primitive widening, as per:
            // http://docs.oracle.com/javase/specs/jls/se7/html/jls-5.html#jls-5.1.2
            // Widening

            // We need to support more liberal conversion
            // i.e. textView#setTextView(0, 60); should be ok (setTextView param
            // types are (int, float))
            // TODO Avoid matching byte if the arg is a number type that woudl
            // overflow?
            // Or at least increase distance?
            if (byte.class.equals(target)) {
                if (Byte.class.equals(argument)) { // signed 8-bit
                    return Match.EXACT;
                }
                if (Short.class.equals(argument)) { // signed 16-bit
                    return 1;
                }
                if (Integer.class.equals(argument)) {
                    return 2;
                }
                if (Long.class.equals(argument)) {
                    return 3;
                }
                if (Float.class.equals(argument)) {
                    return 4;
                }
                if (Double.class.equals(argument)) {
                    return 5;
                }
            }
            else if (short.class.equals(target)) {
                if (Byte.class.equals(argument)) {
                    return 1;
                }
                if (Short.class.equals(argument)) { // signed 16-bit
                    return Match.EXACT;
                }
                if (Integer.class.equals(argument)) {
                    return 1;
                }
                if (Long.class.equals(argument)) {
                    return 2;
                }
                if (Float.class.equals(argument)) {
                    return 3;
                }
                if (Double.class.equals(argument)) {
                    return 4;
                }
            }
            else if (int.class.equals(target)) {
                if (Byte.class.equals(argument)) {
                    return 2;
                }
                if (Short.class.equals(argument)) {
                    return 1;
                }
                if (Integer.class.equals(argument)) {
                    return Match.EXACT;
                }
                if (Long.class.equals(argument)) {
                    return 1;
                }
                if (Float.class.equals(argument)) {
                    return 2;
                }
                if (Double.class.equals(argument)) {
                    return 3;
                }
            }
            else if (long.class.equals(target)) {
                if (Byte.class.equals(argument)) {
                    return 3;
                }
                if (Short.class.equals(argument)) {
                    return 2;
                }
                if (Integer.class.equals(argument)) {
                    return 1;
                }
                if (Long.class.equals(argument)) {
                    return Match.EXACT;
                }
                if (Float.class.equals(argument)) {
                    return 1;
                }
                if (Double.class.equals(argument)) {
                    return 2;
                }
            }
            else if (float.class.equals(target)) {
                if (Byte.class.equals(argument)) {
                    return 4;
                }
                if (Short.class.equals(argument)) {
                    return 3;
                }
                if (Integer.class.equals(argument)) {
                    return 2;
                }
                if (Long.class.equals(argument)) {
                    return 1;
                }
                if (Float.class.equals(argument)) {
                    return Match.EXACT;
                }
                if (Double.class.equals(argument)) {
                    return 1;
                }
            }
            else if (double.class.equals(target)) {
                if (Byte.class.equals(argument)) {
                    return 5;
                }
                if (Short.class.equals(argument)) {
                    return 4;
                }
                if (Integer.class.equals(argument)) {
                    return 3;
                }
                if (Long.class.equals(argument)) {
                    return 2;
                }
                if (Float.class.equals(argument)) {
                    return 1;
                }
                if (Double.class.equals(argument)) {
                    return Match.EXACT;
                }
            }
            else if (char.class.equals(target)) {
                // Integer in valid range is nearly an exact match
                if (Integer.class.equals(argument)) {
                    Number num = (Number) arg;
                    int asInt = num.intValue();
                    if (asInt >= 0 && asInt <= Character.MAX_VALUE) {
                        return 1;
                    }
                }
                // String of length == 1 is an exact match
                else if (String.class.equals(argument)) {
                    String stringArg = (String) arg;
                    if (stringArg.length() == 1) {
                        return Match.EXACT;
                    }
                }
            }
            else if (boolean.class.equals(target) && Boolean.class.equals(argument)) {
                return Match.EXACT;
            }
            return Match.NO_MATCH;
        } else if (target.isArray()) {
            // treat string -> char[] special
            if (String.class.equals(argument) && char[].class.equals(target)) {
                return Match.EXACT;
            }
            // TODO If we're expecting an array, we should allow List or array args

            // Handle arrays
            if (argument.isArray()) {
                Class<?> component = target.getComponentType();
                // Now ensure that the array elements are all compatible with the target array's component type
                // For this we measure the distance of each element and sum them all together.
                int length = Array.getLength(arg);
                int sum = 0;
                for (int i = 0; i < length; i++) {
                    int elementDistance = matchArg(component, Array.get(arg, i));
                    if (elementDistance == Match.NO_MATCH) {
                        return Match.NO_MATCH;
                    }
                    sum += elementDistance;
                }
                return sum;
            }
        }

        // Non-primitives
        if (!isAssignable(target, argument, arg)) {
            return Match.NO_MATCH;
        }

        // How far are the two types in the type hierarchy?
        return 100 * hops(argument, target, 0);
    }

    private static boolean isAssignable(Class<?> target, Class<?> fromType, Object object) {
        if (target.isAssignableFrom(fromType)) {
            return true;
        }
        // FIXME Handle converting com.android.view.View -> org.appcelerator.titanium.proxy.TiViewProxy
        if (ActivityProxy.class.equals(target)) {
            return (object instanceof Activity);
        } else if (IntentProxy.class.equals(target)) {
            return (object instanceof Intent);
        }
        return false;
    }

    /**
     * Try to use recursion to determine how many types away in the type
     * hierarchy the target type is.
     *
     * @param src
     * @param target
     * @param hops
     * @return
     */
    private static int hops(Class<?> src, Class<?> target, int hops) {
        // FIXME This is pretty slow and can result in some deep recursion in
        // some cases...
        // Can we do better?
        // If we know the target is an interface, is there a point in searching
        // super classes (other than looking at it's interfaces?)
        if (src == null) {
            return -1; // end of recursion, no parent type!
        }

        // they're the same class, no hops up the hierarchy
        if (target.equals(src)) {
            return hops;
        }

        // return 100 hops when converting between Activity <-> ActivityProxy, Intent <-> IntentProxy
        if (ActivityProxy.class.equals(target) && Activity.class.isAssignableFrom(src)) {
            return 100;
        } else if (IntentProxy.class.equals(target) && Intent.class.isAssignableFrom(src)) {
            return 100;
        }

        // Take the least hops of traversing the parent type...
        int result = hops(src.getSuperclass(), target, hops + 1);

        // or the interfaces...
        Class<?>[] interfaces = src.getInterfaces();
        if (interfaces != null && interfaces.length > 0) {
            for (int i = 0; i < interfaces.length; i++) {
                int interfaceHops = hops(interfaces[i], target, hops + 1);
                if (interfaceHops > -1 && (result == -1 || interfaceHops < result)) {
                    // match up the interface hierarchy
                    result = interfaceHops;
                }
            }
        }
        return result;
    }

    /**
     * Represents a Method match. Holds the method that matched along with an
     * integer representing how close or distant the match is. Lower distance ==
     * better match.
     *
     * @author cwilliams
     */
    private static class Match<T> implements Comparable<Match<T>> {

        public static final int NO_MATCH = -1;
        public static final int EXACT = 0;

        public int distance;
        public T method;

        Match(T m, int dist) {
            this.distance = dist;
            this.method = m;
        }

        public boolean isExact() {
            return distance == EXACT;
        }

        @Override
        public int compareTo(Match<T> another) {
            return distance - another.distance;
        }

        @Override
        public String toString() {
            return method.toString() + ", distance: " + distance;
        }
    }
}
