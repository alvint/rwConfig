package net.rabbitware.config;
import java.util.List;

sealed abstract class Value permits
        Value.Integer, Value.Long, Value.Double, Value.String, Value.Boolean,
        Value.IntegerList, Value.LongList, Value.DoubleList, Value.StringList, Value.BooleanList {

    @Override
    public java.lang.String toString() {
        return switch (this) {
            case Value.Integer i -> java.lang.String.valueOf(i.i);
            case Value.Long l -> java.lang.String.valueOf(l.l);
            case Value.Double d -> java.lang.String.valueOf(d.d);
            case Value.String s -> s.s;
            case Value.Boolean b -> java.lang.String.valueOf(b.b);
            case Value.IntegerList il -> il.list.toString();
            case Value.LongList ll -> ll.list.toString();
            case Value.DoubleList dl -> dl.list.toString();
            case Value.StringList sl -> sl.list.toString();
            case Value.BooleanList bl -> bl.list.toString();
        };
    }

    static final class Boolean extends Value {
        final boolean b;

        public Boolean(boolean b) {
            this.b = b;
        }
    }

    static final class Integer extends Value {
        final int i;

        public Integer(int i) {
            this.i = i;
        }
    }

    static final class Long extends Value {
        final long l;

        public Long(long l) {
            this.l = l;
        }
    }

    static final class Double extends Value {
        final double d;

        public Double(double d) {
            this.d = d;
        }
    }

    static final class String extends Value {
        final java.lang.String s;

        public String(java.lang.String s) {
            this.s = s;
        }
    }

    static final class BooleanList extends Value {
        final List<Value.Boolean> list;

        public BooleanList(List<Value.Boolean> list) {
            this.list = List.copyOf(list);
        }
    }

    static final class IntegerList extends Value {
        final List<Value.Integer> list;

        public IntegerList(List<Value.Integer> list) {
            this.list = List.copyOf(list);
        }
    }

    static final class LongList extends Value {
        final List<Value.Long> list;

        public LongList(List<Value.Long> list) {
            this.list = List.copyOf(list);
        }
    }

    static final class DoubleList extends Value {
        final List<Value.Double> list;

        public DoubleList(List<Value.Double> list) {
            this.list = List.copyOf(list);
        }
    }

    static final class StringList extends Value {
        final List<Value.String> list;

        public StringList(List<Value.String> list) {
            this.list = List.copyOf(list);
        }
    }
}
