package cubism53.mcp;

import com.sun.tools.attach.VirtualMachine;

/** Load our own adapter into the running editor; vendor files are not changed. */
public final class Attach {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("PID agent.jar config.json");
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1], args[2]); }
        finally { vm.detach(); }
        System.out.println("Adapter loaded into PID " + args[0]);
    }
}
