// DONT_TARGET_EXACT_BACKEND: JS JS_IR JS_IR_ES6 WASM NATIVE
// SKIP_JDK6
// MODULE: lib
// FILE: Custom.java

class Custom<K, V> {
    public interface MBiConsumer<T, U> {
        void accept(T t, U u);
    }

    private K k;

    private V v;

    public Custom(K k, V v) {
        this.k = k;
        this.v = v;
    }

    public void forEach(MBiConsumer<? super K, ? super V> action) {
        action.accept(k, v);
    }
}

// MODULE: main(lib)
// FILE: 1.kt

import java.util.Arrays

fun box(): String {
    val instance = Custom<String, String>("O", "K")
    var result = "fail"
    instance.forEach (Custom.MBiConsumer<String, String> { a, b ->
        result = a + b
    })

    val superInterfaces = Arrays.toString((Class.forName("_1Kt\$box$1")).genericInterfaces)
    if (superInterfaces != "[Custom\$MBiConsumer<java.lang.String, java.lang.String>]") {
        return "fail: $superInterfaces"
    }

    return result
}
