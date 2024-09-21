package se.vidstige.jadb.test.fakes;

import se.vidstige.jadb.JadbException;
import se.vidstige.jadb.RemoteFile;
import se.vidstige.jadb.server.AdbBase64;
import se.vidstige.jadb.server.AdbConnection;
import se.vidstige.jadb.server.AdbCrypto;
import se.vidstige.jadb.server.AdbDeviceResponder;
import se.vidstige.jadb.server.AdbResponder;
import se.vidstige.jadb.server.AdbServer;
import se.vidstige.jadb.server.AdbStream;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Created by vidstige on 2014-03-20.
 */
public class FakeAdbServer implements AdbResponder {
    private final AdbServer server;
    private final List<DeviceResponder> devices = new ArrayList<>();

    private AdbCrypto crypto;


    public FakeAdbServer(int port) {
        server = new AdbServer(this, port);
    }

    public void start() throws InterruptedException {
        System.out.println("Starting fake on port " + server.getPort());
        server.start();
    }

    public void stop() throws IOException, InterruptedException {
        System.out.println("Stopping fake on port " + server.getPort());
        server.stop();
    }

    @Override
    public void onCommand(String command) {
        System.out.println("command: " + command);
    }

    public static AdbBase64 getBase64Impl() {
        return new AdbBase64() {
            @Override
            public String encodeToString(byte[] arg0) {
                return Base64.getEncoder().encodeToString(arg0);
            }
        };
    }

    private AdbCrypto setupCrypto() throws NoSuchAlgorithmException, IOException {

        AdbCrypto c = null;
        try {
            File privKey = new File("priv.key");

            FileInputStream privIn = new FileInputStream("priv.key");
            FileInputStream pubIn = new FileInputStream("pub.key");
            c = AdbCrypto.loadAdbKeyPair(getBase64Impl(), privIn, pubIn);
        } catch (IOException | InvalidKeySpecException | NoSuchAlgorithmException | NullPointerException e) {
            // Failed to read from file
            c = null;
        }


        if (c == null) {
            // We couldn't load a key, so let's generate a new one
            c = AdbCrypto.generateAdbKeyPair(getBase64Impl());

            // Save it
            FileOutputStream privOut = new FileOutputStream("priv.key");
            FileOutputStream pubOut = new FileOutputStream("priv.key");

            c.saveAdbKeyPair(privOut, pubOut);
            //Generated new keypair
        } else {
            //Loaded existing keypair
        }

        return c;
    }

    @Override
    public int getVersion() {
        return 31;
    }

    public void add(String serial) {
        devices.add(new DeviceResponder(serial, "device", null));
    }

    public void add(String serial, String type) {
        devices.add(new DeviceResponder(serial, type, null));
    }

    public void verifyExpectations() {
        for (DeviceResponder d : devices)
            d.verifyExpectations();
    }

    public interface ExpectationBuilder {
        void failWith(String message);

        void withContent(byte[] content);

        void withContent(String content);
    }

    private DeviceResponder findBySerial(String serial) {
        for (DeviceResponder d : devices) {
            if (d.getSerial().equals(serial)) return d;
        }
        return null;
    }

    public ExpectationBuilder expectPush(String serial, RemoteFile path) {
        return findBySerial(serial).expectPush(path);
    }

    public ExpectationBuilder expectPull(String serial, RemoteFile path) {
        return findBySerial(serial).expectPull(path);
    }

    public DeviceResponder.ShellExpectation expectShell(String serial, String commands) {
        return findBySerial(serial).expectShell(commands);
    }

    public void expectTcpip(String serial, Integer port) {
        findBySerial(serial).expectTcpip(port);
    }

    public DeviceResponder.ListExpectation expectList(String serial, String remotePath) {
        return findBySerial(serial).expectList(remotePath);
    }

    @Override
    public List<AdbDeviceResponder> getDevices() {
        return new ArrayList<AdbDeviceResponder>(devices);
    }

    @Override
    public boolean isDeviceConnected(String serial) {
        return findBySerial(serial) != null;
    }

    @Override
    public boolean onDeviceConnect(String serial) {
        Socket sock = null;
        boolean result = false;
        String[] deviceAddr = serial.split(":");
        if (deviceAddr.length != 2) {
            return result;
        }
        try {
            sock = new Socket(deviceAddr[0], Integer.parseInt(deviceAddr[1]));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            AdbConnection adb = AdbConnection.create(sock, crypto);
            adb.connect();
            devices.add(new DeviceResponder(serial, "device", adb));
            result = true;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private static class DeviceResponder implements AdbDeviceResponder {
        private final String serial;
        private final String type;
        private List<FileExpectation> fileExpectations = new ArrayList<>();
        private List<ShellExpectation> shellExpectations = new ArrayList<>();
        private List<ListExpectation> listExpectations = new ArrayList<>();
        private List<Integer> tcpipExpectations = new ArrayList<>();

        private AdbConnection adbConnection;

        private DeviceResponder(String serial, String type, AdbConnection adb) {
            this.serial = serial;
            this.type = type;
            this.adbConnection = adb;
        }

        @Override
        public String getSerial() {
            return serial;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public void filePushed(RemoteFile path, int mode, ByteArrayOutputStream buffer) throws JadbException {
            for (FileExpectation fe : fileExpectations) {
                if (fe.matches(path)) {
                    fileExpectations.remove(fe);
                    fe.throwIfFail();
                    fe.verifyContent(buffer.toByteArray());
                    return;
                }
            }
            throw new JadbException("Unexpected push to device " + serial + " at " + path);
        }

        @Override
        public void filePulled(RemoteFile path, ByteArrayOutputStream buffer) throws JadbException, IOException {
            for (FileExpectation fe : fileExpectations) {
                if (fe.matches(path)) {
                    fileExpectations.remove(fe);
                    fe.throwIfFail();
                    fe.returnFile(buffer);
                    return;
                }
            }
            throw new JadbException("Unexpected push to device " + serial + " at " + path);
        }

        @Override
        public void shell(String command, DataOutputStream stdout, DataInput stdin) throws IOException {
            try {
                AdbStream stream = adbConnection.open("shell:");
                stream.write(command);
                boolean done = false;
                while (!done) {
                    byte[] responseBytes = stream.read();
                    String response = new String(responseBytes, "US-ASCII");
                    if (response.endsWith("$ ") || response.endsWith("# ")) {
                        done = true;
                        stdout.writeBytes(response);
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

//            for (ShellExpectation se : shellExpectations) {
//                if (se.matches(command)) {
//                    shellExpectations.remove(se);
//                    se.writeOutputTo(stdout);
//                    return;
//                }
//            }
//            throw new ProtocolException("Unexpected shell to device " + serial + ": " + command);
        }

        @Override
        public void enableIpCommand(String port, DataOutputStream outputStream) throws IOException {
            for (Integer expectation : tcpipExpectations) {
                if (expectation == Integer.parseInt(port)) {
                    tcpipExpectations.remove(expectation);
                    return;
                }
            }

            throw new ProtocolException("Unexpected tcpip to device " + serial + ": (port) " + port);

        }

        @Override
        public List<RemoteFile> list(String path) throws IOException {
            for (ListExpectation le : listExpectations) {
                if (le.matches(path)) {
                    listExpectations.remove(le);
                    return le.getFiles();
                }
            }
            throw new ProtocolException("Unexpected list of device " + serial + " in dir " + path);
        }

        public void verifyExpectations() {
            for (FileExpectation expectation : fileExpectations) {
                org.junit.Assert.fail(expectation.toString());
            }
            for (ShellExpectation expectation : shellExpectations) {
                org.junit.Assert.fail(expectation.toString());
            }
            for (ListExpectation expectation : listExpectations) {
                org.junit.Assert.fail(expectation.toString());
            }
            for (int expectation : tcpipExpectations) {
                org.junit.Assert.fail("Expected tcp/ip on" + expectation);
            }
        }

        private static class FileExpectation implements ExpectationBuilder {
            private final RemoteFile path;
            private byte[] content;
            private String failMessage;

            public FileExpectation(RemoteFile path) {
                this.path = path;
                content = null;
                failMessage = null;
            }

            @Override
            public void failWith(String message) {
                failMessage = message;
            }

            @Override
            public void withContent(byte[] content) {
                this.content = content;
            }

            @Override
            public void withContent(String content) {
                this.content = content.getBytes(StandardCharsets.UTF_8);
            }

            public boolean matches(RemoteFile path) {
                return this.path.equals(path);
            }

            public void throwIfFail() throws JadbException {
                if (failMessage != null) throw new JadbException(failMessage);
            }

            public void verifyContent(byte[] content) {
                org.junit.Assert.assertArrayEquals(this.content, content);
            }

            public void returnFile(ByteArrayOutputStream buffer) throws IOException {
                buffer.write(content);
            }

            @Override
            public String toString() {
                return "Expected file " + path;
            }
        }

        public static class ShellExpectation {
            private final String command;
            private byte[] stdout;

            public ShellExpectation(String command) {
                this.command = command;
            }

            public boolean matches(String command) {
                return command.equals(this.command);
            }

            public void returns(String stdout) {
                this.stdout = stdout.getBytes(StandardCharsets.UTF_8);
            }

            public void writeOutputTo(DataOutputStream stdout) throws IOException {
                stdout.write(this.stdout);
            }
            
            @Override
            public String toString() {
                return "Expected shell " + command;
            }
        }

        public static class ListExpectation {

            private final String remotePath;
            private final List<RemoteFile> files = new ArrayList<>();

            public ListExpectation(String remotePath) {
                this.remotePath = remotePath;
            }

            public boolean matches(String remotePath) {
                return remotePath.equals(this.remotePath);
            }

            public ListExpectation withFile(String path, int size, int modifyTime) {
                files.add(new MockFileEntry(path, size, modifyTime, false));
                return this;
            }

            public ListExpectation withDir(String path, int modifyTime) {
                files.add(new MockFileEntry(path, -1, modifyTime, true));
                return this;
            }

            public List<RemoteFile> getFiles() {
                return Collections.unmodifiableList(files);
            }
            
            @Override
            public String toString() {
                return "Expected file list " + remotePath;
            }

            private static class MockFileEntry extends RemoteFile {

                private final int size;
                private final int modifyTime;
                private final boolean dir;

                MockFileEntry(String path, int size, int modifyTime, boolean dir) {
                    super(path);
                    this.size = size;
                    this.modifyTime = modifyTime;
                    this.dir = dir;
                }

                public int getSize() {
                    return size;
                }

                public int getLastModified() {
                    return modifyTime;
                }

                public boolean isDirectory() {
                    return dir;
                }

            }

        }

        public ExpectationBuilder expectPush(RemoteFile path) {
            FileExpectation expectation = new FileExpectation(path);
            fileExpectations.add(expectation);
            return expectation;
        }

        public ExpectationBuilder expectPull(RemoteFile path) {
            FileExpectation expectation = new FileExpectation(path);
            fileExpectations.add(expectation);
            return expectation;
        }

        public ShellExpectation expectShell(String command) {
            ShellExpectation expectation = new ShellExpectation(command);
            shellExpectations.add(expectation);
            return expectation;
        }

        public ListExpectation expectList(String remotePath) {
            ListExpectation expectation = new ListExpectation(remotePath);
            listExpectations.add(expectation);
            return expectation;
        }

        public void expectTcpip(int port) {
            tcpipExpectations.add(port);
        }
    }
}
