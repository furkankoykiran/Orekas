package com.webrekas.addon.wasm;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.TableInstance;
import com.dylibso.chicory.runtime.WasmFunctionHandle;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class WasmImports {

    private static final String MODULE = "./rust_bg.js";
    private static final long[] EMPTY = new long[0];

    private final ExternRefs refs;
    private Instance instance;
    private TableInstance externRefTable;

    WasmImports(ExternRefs refs) {
        this.refs = refs;
    }

    void bind(Instance instance) {
        this.instance = instance;
        for (int i = 0; i < instance.tableCount(); i++) {
            TableInstance t = instance.table(i);
            if (ValType.ExternRef.equals(t.elementType())) {
                this.externRefTable = t;
                break;
            }
        }
    }

    HostFunction[] functions() {
        return new HostFunction[] {
            hf("__wbg_set_6c60b2e8ad0e9383",
               FunctionType.of(List.of(ValType.ExternRef, ValType.I32, ValType.ExternRef), List.of()),
               this::setArrIndexed),
            hf("__wbg_set_6be42768c690e380",
               FunctionType.of(List.of(ValType.ExternRef, ValType.ExternRef, ValType.ExternRef), List.of()),
               this::setObjKeyed),
            hf("__wbg_new_f3c9df4f38f3f798",
               FunctionType.of(List.of(), List.of(ValType.ExternRef)),
               this::newArray),
            hf("__wbg_new_4f9fafbb3909af72",
               FunctionType.of(List.of(), List.of(ValType.ExternRef)),
               this::newObject),
            hf("__wbg___wbindgen_throw_81fc77679af83bc6",
               FunctionType.of(List.of(ValType.I32, ValType.I32), List.of()),
               this::throwErr),
            hf("__wbg___wbindgen_debug_string_dd5d2d07ce9e6c57",
               FunctionType.of(List.of(ValType.I32, ValType.ExternRef), List.of()),
               this::debugString),
            hf("__wbindgen_init_externref_table",
               FunctionType.of(List.of(), List.of()),
               this::initExternRefTable),
            hf("__wbindgen_cast_0000000000000001",
               FunctionType.of(List.of(ValType.F64), List.of(ValType.ExternRef)),
               this::castNumberToRef),
            hf("__wbindgen_cast_0000000000000002",
               FunctionType.of(List.of(ValType.I32, ValType.I32), List.of(ValType.ExternRef)),
               this::castStringToRef),
        };
    }

    private static HostFunction hf(String name, FunctionType type, WasmFunctionHandle handler) {
        return new HostFunction(MODULE, name, type, handler);
    }

    // arr[i] = v
    private long[] setArrIndexed(Instance inst, long... args) {
        long arrId = args[0];
        int  idx   = (int) args[1];
        long valId = args[2];
        var list = refs.asList(arrId);
        if (list != null) {
            while (list.size() <= idx) list.add(null);
            list.set(idx, refs.get(valId));
        }
        return EMPTY;
    }

    // obj[k] = v
    private long[] setObjKeyed(Instance inst, long... args) {
        long objId = args[0];
        long keyId = args[1];
        long valId = args[2];
        var map = refs.asMap(objId);
        if (map != null) {
            Object k = refs.get(keyId);
            map.put(k == null ? "null" : k.toString(), refs.get(valId));
        }
        return EMPTY;
    }

    private long[] newArray(Instance inst, long... args) {
        return new long[] { refs.newArray() };
    }

    private long[] newObject(Instance inst, long... args) {
        return new long[] { refs.newObject() };
    }

    private long[] throwErr(Instance inst, long... args) {
        int ptr = (int) args[0];
        int len = (int) args[1];
        throw new RuntimeException("wasm throw: " + readUtf8(ptr, len));
    }

    private long[] debugString(Instance inst, long... args) {
        int  resultPtr = (int) args[0];
        long refId     = args[1];
        Object v = refs.get(refId);
        String s = v == null ? "null" : v.toString();
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        var malloc = inst.export("__wbindgen_malloc");
        long[] r = malloc.apply(bytes.length, 1);
        int ptr = (int) r[0];
        Memory mem = inst.memory();
        mem.write(ptr, bytes);
        mem.writeI32(resultPtr + 0, ptr);
        mem.writeI32(resultPtr + 4, bytes.length);
        return EMPTY;
    }

    private long[] initExternRefTable(Instance inst, long... args) {
        TableInstance t = externRefTable;
        if (t == null) {
            for (int i = 0; i < inst.tableCount(); i++) {
                TableInstance cand = inst.table(i);
                if (ValType.ExternRef.equals(cand.elementType())) { t = cand; break; }
            }
        }
        if (t != null) {
            int prev = t.grow(4, 0, inst);
            t.setRef(0, 0, inst);                 // undefined
            t.setRef(prev + 0, 1, inst);          // undefined
            t.setRef(prev + 1, 2, inst);          // null
            t.setRef(prev + 2, 3, inst);          // true
            t.setRef(prev + 3, 4, inst);          // false
        }
        return EMPTY;
    }

    private long[] castNumberToRef(Instance inst, long... args) {
        double d = Double.longBitsToDouble(args[0]);
        return new long[] { refs.alloc(d) };
    }

    private long[] castStringToRef(Instance inst, long... args) {
        int ptr = (int) args[0];
        int len = (int) args[1];
        return new long[] { refs.alloc(readUtf8(ptr, len)) };
    }

    private String readUtf8(int ptr, int len) {
        Memory mem = instance.memory();
        byte[] buf = mem.readBytes(ptr, len);
        return new String(buf, StandardCharsets.UTF_8);
    }
}
