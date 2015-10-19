package org.msgpack.jruby;


import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyObject;
import org.jruby.RubyArray;
import org.jruby.RubyHash;
import org.jruby.RubyIO;
import org.jruby.RubyInteger;
import org.jruby.RubyFixnum;
import org.jruby.RubyString;
import org.jruby.RubySymbol;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.util.ByteList;

import static org.jruby.runtime.Visibility.PRIVATE;

@JRubyClass(name="MessagePack::Factory")
public class Factory extends RubyObject {
  private Ruby runtime;
  private Packer.ExtRegistry packerExtRegistry;
  private Unpacker.ExtRegistry unpackerExtRegistry;

  public Factory(Ruby runtime, RubyClass type) {
    super(runtime, type);
    this.runtime = runtime;
  }

  static class FactoryAllocator implements ObjectAllocator {
    public IRubyObject allocate(Ruby runtime, RubyClass type) {
      return new Factory(runtime, type);
    }
  }

  @JRubyMethod(name = "initialize")
  public IRubyObject initialize(ThreadContext ctx) {
    this.packerExtRegistry = new Packer.ExtRegistry(ctx.getRuntime());
    this.unpackerExtRegistry = new Unpacker.ExtRegistry(ctx.getRuntime());
    return this;
  }

  public Packer.ExtRegistry packerRegistry() {
    return this.packerExtRegistry.dup();
  }

  public Unpacker.ExtRegistry unpackerRegistry() {
    return this.unpackerExtRegistry.dup();
  }

  @JRubyMethod(name = "packer", optional = 1)
  public Packer packer(ThreadContext ctx, IRubyObject[] args) {
    return Packer.newPacker(ctx, packerRegistry(), args);
  }

  @JRubyMethod(name = "unpacker", optional = 1)
  public Unpacker unpacker(ThreadContext ctx, IRubyObject[] args) {
    return Unpacker.newUnpacker(ctx, unpackerRegistry(), args);
  }

  @JRubyMethod(name = "registered_types_internal", visibility = PRIVATE)
  public IRubyObject registeredTypesInternal(ThreadContext ctx) {
    Unpacker.ExtRegistry reg = unpackerRegistry();
    RubyHash mapping = RubyHash.newHash(ctx.getRuntime());
    for (int i = 0; i < 256; i++) {
      if (reg.array[i] != null) {
        mapping.fastASet(RubyFixnum.newFixnum(ctx.getRuntime(), i - 128), reg.array[i]);
      }
    }

    IRubyObject[] ary = { packerRegistry().hash, mapping };
    return RubyArray.newArray(ctx.getRuntime(), ary);
  }

  @JRubyMethod(name = "register_type", required = 2, optional = 1)
  public IRubyObject registerType(ThreadContext ctx, IRubyObject[] args) {
    Ruby runtime = ctx.getRuntime();
    IRubyObject type = args[0];
    IRubyObject klass = args[1];

    IRubyObject packerArg;
    IRubyObject unpackerArg;
    if (args.length == 2) {
      packerArg = runtime.newSymbol("to_msgpack_ext");
      unpackerArg = runtime.newSymbol("from_msgpack_ext");
    } else if (args.length == 3) {
      if (args[args.length - 1] instanceof RubyHash) {
        RubyHash options = (RubyHash) args[args.length - 1];
        packerArg = options.fastARef(runtime.newSymbol("packer"));
        unpackerArg = options.fastARef(runtime.newSymbol("unpacker"));
      } else {
        throw runtime.newArgumentError(String.format("expected Hash but found %s.", args[args.length - 1].getType().getName()));
      }
    } else {
      throw runtime.newArgumentError(String.format("wrong number of arguments (%d for 2..3)", 2 + args.length));
    }

    long typeId = ((RubyFixnum) type).getLongValue();
    if (typeId < -128 || typeId > 127) {
      throw runtime.newRangeError(String.format("integer %d too big to convert to `signed char'", typeId));
    }

    if (!(klass instanceof RubyClass)) {
      throw runtime.newArgumentError(String.format("expected Class but found %s.", klass.getType().getName()));
    }
    RubyClass extClass = (RubyClass) klass;

    IRubyObject packerProc = runtime.getNil();
    IRubyObject unpackerProc = runtime.getNil();
    if (packerArg != null) {
      packerProc = packerArg.callMethod(ctx, "to_proc");
    }
    if (unpackerArg != null) {
      if (unpackerArg instanceof RubyString || unpackerArg instanceof RubySymbol) {
        unpackerProc = extClass.method(unpackerArg.callMethod(ctx, "to_sym"));
      } else {
        unpackerProc = unpackerArg.callMethod(ctx, "method", runtime.newSymbol("call"));
      }
    }

    this.packerExtRegistry.put(extClass, (int) typeId, packerProc, packerArg);
    this.unpackerExtRegistry.put(extClass, (int) typeId, unpackerProc, unpackerArg);

    return runtime.getNil();
  }
}
