/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.lint.checks;

import static com.android.tools.lint.detector.api.LintConstants.DOT_XML;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.lint.client.api.LintClient;
import com.android.tools.lint.detector.api.LintUtils;
import com.google.common.base.Charsets;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.common.primitives.UnsignedBytes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Database for API checking: Allows quick lookup of a given class, method or field
 * to see which API level it was introduced in.
 * <p>
 * This class is optimized for quick bytecode lookup used in conjunction with the
 * ASM library: It has lookup methods that take internal JVM signatures, and for a method
 * call for example it processes the owner, name and description parameters separately
 * the way they are provided from ASM.
 * <p>
 * The {@link Api} class provides access to the full Android API along with version
 * information, initialized from an XML file. This lookup class adds a binary cache around
 * the API to make initialization faster and to require fewer objects. It creates
 * a binary cache data structure, which fits in a single byte array, which means that
 * to open the database you can just read in the byte array and go. On one particular
 * machine, this takes about 30-50 ms versus 600-800ms for the full parse. It also
 * helps memory by placing everything in a compact byte array instead of needing separate
 * strings (2 bytes per character in a char[] for the 25k method entries, 11k field entries
 * and 6k class entries) - and it also avoids the same number of Map.Entry objects.
 * When creating the memory data structure it performs a few other steps to help memory:
 * <ul>
 * <li> It stores the strings as single bytes, since all the JVM signatures are in ASCII
 * <li> It strips out the method return types (which takes the binary size down from
 *      about 4.7M to 4.0M)
 * <li> It strips out all APIs that have since=1, since the lookup only needs to find
 *      classes, methods and fields that have an API level *higher* than 1. This drops
 *      the memory use down from 4.0M to 1.7M.
 * </ul>
 */
public class ApiLookup {
    /** Relative path to the api-versions.xml database file within the Lint installation */
    private static final String XML_FILE_PATH = "platform-tools/api/api-versions.xml"; //$NON-NLS-1$
    private static final String FILE_HEADER = "API database used by Android lint\000";
    private static final int BINARY_FORMAT_VERSION = 3;
    private static final boolean DEBUG_FORCE_REGENERATE_BINARY = false;
    private static final boolean DEBUG_SEARCH = false;
    private static final boolean WRITE_STATS = false;
    /** Default size to reserve for each API entry when creating byte buffer to build up data */
    private static final int BYTES_PER_ENTRY = 40;

    private final LintClient mClient;
    private final File mXmlFile;
    private final File mBinaryFile;
    private final Api mInfo;
    private byte[] mData;
    private int[] mIndices;
    private int mClassCount;
    private int mMethodCount;

    private static WeakReference<ApiLookup> sInstance =
            new WeakReference<ApiLookup>(null);

    /**
     * Returns an instance of the API database
     *
     * @param client the client to associate with this database - used only for
     *            logging. The database object may be shared among repeated invocations,
     *            and in that case client used will be the one originally passed in.
     *            In other words, this parameter may be ignored if the client created
     *            is not new.
     * @return a (possibly shared) instance of the API database, or null
     *         if its data can't be found
     */
    public static ApiLookup get(LintClient client) {
        synchronized (ApiLookup.class) {
            ApiLookup db = sInstance.get();
            if (db == null) {
                File file = client.findResource(XML_FILE_PATH);
                if (file == null) {
                    // AOSP build environment?
                    String build = System.getenv("ANDROID_BUILD_TOP");   //$NON-NLS-1$
                    if (build != null) {
                        file = new File(build, "development/sdk/api-versions.xml" //$NON-NLS-1$
                                .replace('/', File.separatorChar));
                    }
                }

                if (file == null || !file.exists()) {
                    client.log(null, "Fatal error: No API database found at %1$s", file);
                    return null;
                } else {
                    db = get(client, file);
                }
                sInstance = new WeakReference<ApiLookup>(db);
            }

            return db;
        }
    }

    /**
     * Returns an instance of the API database
     *
     * @param client the client to associate with this database - used only for
     *            logging
     * @param xmlFile the XML file containing configuration data to use for this
     *            database
     * @return a (possibly shared) instance of the API database, or null
     *         if its data can't be found
     */
    public static ApiLookup get(LintClient client, File xmlFile) {
        if (!xmlFile.exists()) {
            client.log(null, "The API database file %1$s does not exist", xmlFile);
            return null;
        }

        String name = xmlFile.getName();
        if (LintUtils.endsWith(name, DOT_XML)) {
            name = name.substring(0, name.length() - DOT_XML.length());
        }
        File cacheDir = client.getCacheDir(true/*create*/);
        if (cacheDir == null) {
            cacheDir = xmlFile.getParentFile();
        }

        File binaryData = new File(cacheDir, name
                // Incorporate version number in the filename to avoid upgrade filename
                // conflicts on Windows (such as issue #26663)
                + "-" + BINARY_FORMAT_VERSION + ".bin"); //$NON-NLS-1$ //$NON-NLS-2$

        if (DEBUG_FORCE_REGENERATE_BINARY) {
            System.err.println("\nTemporarily regenerating binary data unconditionally \nfrom "
                    + xmlFile + "\nto " + binaryData);
            if (!createCache(client, xmlFile, binaryData)) {
                return null;
            }
        } else if (!binaryData.exists() || binaryData.lastModified() < xmlFile.lastModified()) {
            if (!createCache(client, xmlFile, binaryData)) {
                return null;
            }
        }

        if (!binaryData.exists()) {
            client.log(null, "The API database file %1$s does not exist", binaryData);
            return null;
        }

        return new ApiLookup(client, xmlFile, binaryData, null);
    }

    private static boolean createCache(LintClient client, File xmlFile, File binaryData) {
        long begin = 0;
        if (WRITE_STATS) {
            begin = System.currentTimeMillis();
        }

        Api info = Api.parseApi(xmlFile);

        if (WRITE_STATS) {
            long end = System.currentTimeMillis();
            System.out.println("Reading XML data structures took " + (end - begin) + " ms)");
        }

        if (info != null) {
            try {
                writeDatabase(binaryData, info);
                return true;
            } catch (IOException ioe) {
                client.log(ioe, "Can't write API cache file");
            }
        }

        return false;
    }

    /** Use one of the {@link #get} factory methods instead */
    private ApiLookup(
            @NonNull LintClient client,
            @NonNull File xmlFile,
            @Nullable File binaryFile,
            @Nullable Api info) {
        mClient = client;
        mXmlFile = xmlFile;
        mBinaryFile = binaryFile;
        mInfo = info;

        if (binaryFile != null) {
            readData();
        }
    }

    /**
     * Database format:
     * <pre>
     * 1. A file header, which is the exact contents of {@link FILE_HEADER} encoded
     *     as ASCII characters. The purpose of the header is to identify what the file
     *     is for, for anyone attempting to open the file.
     * 2. A file version number. If the binary file does not match the reader's expected
     *     version, it can ignore it (and regenerate the cache from XML).
     * 3. The number of classes [1 int]
     * 4. The number of members (across all classes) [1 int].
     * 5. Class offset table (one integer per class, pointing to the byte offset in the
     *      file (relative to the beginning of the file) where each class begins.
     *      The classes are always sorted alphabetically by fully qualified name.
     * 6. Member offset table (one integer per member, pointing to the byte offset in the
     *      file (relative to the beginning of the file) where each member entry begins.
     *      The members are always sorted alphabetically.
     * 7. Class entry table. Each class entry consists of the fully qualified class name,
     *       in JVM format (using / instead of . in package names and $ for inner classes),
     *       followed by the byte 0 as a terminator, followed by the API version as a byte.
     * 8. Member entry table. Each member entry consists of the class number (as a short),
     *      followed by the JVM method/field signature, encoded as UTF-8, followed by a 0 byte
     *      signature terminator, followed by the API level as a byte.
     * <p>
     * TODO: Pack the offsets: They increase by a small amount for each entry, so no need
     * to spend 4 bytes on each. These will need to be processed when read back in anyway,
     * so consider storing the offset -deltas- as single bytes and adding them up cumulatively
     * in readData().
     * </pre>
     */
    private void readData() {
        if (!mBinaryFile.exists()) {
            mClient.log(null, "%1$s does not exist", mBinaryFile);
            return;
        }
        long start = System.currentTimeMillis();
        try {
            MappedByteBuffer buffer = Files.map(mBinaryFile, MapMode.READ_ONLY);
            assert buffer.order() == ByteOrder.BIG_ENDIAN;

            // First skip the header
            byte[] expectedHeader = FILE_HEADER.getBytes(Charsets.US_ASCII);
            buffer.rewind();
            for (int offset = 0; offset < expectedHeader.length; offset++) {
                if (expectedHeader[offset] != buffer.get()) {
                    mClient.log(null, "Incorrect file header: not an API database cache " +
                            "file, or a corrupt cache file");
                    return;
                }
            }

            // Read in the format number
            if (buffer.get() != BINARY_FORMAT_VERSION) {
                // Force regeneration of new binary data with up to date format
                if (createCache(mClient, mXmlFile, mBinaryFile)) {
                    readData(); // Recurse
                }

                return;
            }

            mClassCount = buffer.getInt();
            mMethodCount = buffer.getInt();

            // Read in the class table indices;
            int count = mClassCount + mMethodCount;
            int[] offsets = new int[count];

            // Another idea: I can just store the DELTAS in the file (and add them up
            // when reading back in) such that it takes just ONE byte instead of four!

            for (int i = 0; i < count; i++) {
                offsets[i] = buffer.getInt();
            }

            // No need to read in the rest -- we'll just keep the whole byte array in memory
            // TODO: Make this code smarter/more efficient.
            int size = buffer.limit();
            byte[] b = new byte[size];
            buffer.rewind();
            buffer.get(b);
            mData = b;
            mIndices = offsets;

            // TODO: We only need to keep the data portion here since we've initialized
            // the offset array separately.
            // TODO: Investigate (profile) accessing the byte buffer directly instead of
            // accessing a byte array.
        } catch (IOException e) {
            mClient.log(e, null);
        }
        if (WRITE_STATS) {
            long end = System.currentTimeMillis();
            System.out.println("\nRead API database in " + (end - start)
                    + " milliseconds.");
            System.out.println("Size of data table: " + mData.length + " bytes ("
                    + Integer.toString(mData.length/1024) + "k)\n");
        }
    }

    /** See the {@link #readData()} for documentation on the data format. */
    private static void writeDatabase(File file, Api info) throws IOException {
        /*
         * 1. A file header, which is the exact contents of {@link FILE_HEADER} encoded
         *     as ASCII characters. The purpose of the header is to identify what the file
         *     is for, for anyone attempting to open the file.
         * 2. A file version number. If the binary file does not match the reader's expected
         *     version, it can ignore it (and regenerate the cache from XML).
         */
        Map<String, ApiClass> classMap = info.getClasses();
        // Write the class table

        List<String> classes = new ArrayList<String>(classMap.size());
        Map<ApiClass, List<String>> memberMap =
                Maps.newHashMapWithExpectedSize(classMap.size());
        int memberCount = 0;
        for (Map.Entry<String, ApiClass> entry : classMap.entrySet()) {
            String className = entry.getKey();
            ApiClass apiClass = entry.getValue();

            Set<String> allMethods = apiClass.getAllMethods(info);
            Set<String> allFields = apiClass.getAllFields(info);

            // Strip out all members that have been supported since version 1.
            // This makes the database *much* leaner (down from about 4M to about
            // 1.7M), and this just fills the table with entries that ultimately
            // don't help the API checker since it just needs to know if something
            // requires a version *higher* than the minimum. If in the future the
            // database needs to answer queries about whether a method is public
            // or not, then we'd need to put this data back in.
            List<String> members = new ArrayList<String>(allMethods.size() + allFields.size());
            for (String member : allMethods) {
                Integer since = apiClass.getMethod(member, info);
                if (since == null) {
                    assert false : className + ':' + member;
                    since = 1;
                }
                if (since != 1) {
                    members.add(member);
                }
            }

            // Strip out all members that have been supported since version 1.
            // This makes the database *much* leaner (down from about 4M to about
            // 1.7M), and this just fills the table with entries that ultimately
            // don't help the API checker since it just needs to know if something
            // requires a version *higher* than the minimum. If in the future the
            // database needs to answer queries about whether a method is public
            // or not, then we'd need to put this data back in.
            for (String member : allFields) {
                Integer since = apiClass.getField(member, info);
                if (since == null) {
                    assert false : className + ':' + member;
                    since = 1;
                }
                if (since != 1) {
                    members.add(member);
                }
            }

            // Only include classes that have one or more members requiring version 2 or higher:
            if (members.size() > 0) {
                classes.add(className);
                memberMap.put(apiClass, members);
                memberCount += members.size();
            }
        }
        Collections.sort(classes);

        int entryCount = classMap.size() + memberCount;
        int capacity = entryCount * BYTES_PER_ENTRY;
        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        buffer.order(ByteOrder.BIG_ENDIAN);
        //  1. A file header, which is the exact contents of {@link FILE_HEADER} encoded
        //      as ASCII characters. The purpose of the header is to identify what the file
        //      is for, for anyone attempting to open the file.

        buffer.put(FILE_HEADER.getBytes(Charsets.US_ASCII));

        //  2. A file version number. If the binary file does not match the reader's expected
        //      version, it can ignore it (and regenerate the cache from XML).
        buffer.put((byte) BINARY_FORMAT_VERSION);



        //  3. The number of classes [1 int]
        buffer.putInt(classes.size());
        //  4. The number of members (across all classes) [1 int].
        buffer.putInt(memberCount);

        //  5. Class offset table (one integer per class, pointing to the byte offset in the
        //       file (relative to the beginning of the file) where each class begins.
        //       The classes are always sorted alphabetically by fully qualified name.
        int classOffsetTable = buffer.position();

        // Reserve enough room for the offset table here: we will backfill it with pointers
        // as we're writing out the data structures below
        for (int i = 0, n = classes.size(); i < n; i++) {
            buffer.putInt(0);
        }

        //  6. Member offset table (one integer per member, pointing to the byte offset in the
        //       file (relative to the beginning of the file) where each member entry begins.
        //       The members are always sorted alphabetically.
        int methodOffsetTable = buffer.position();
        for (int i = 0, n = memberCount; i < n; i++) {
            buffer.putInt(0);
        }

        int nextEntry = buffer.position();
        int nextOffset = classOffsetTable;

        // 7. Class entry table. Each class entry consists of the fully qualified class name,
        //      in JVM format (using / instead of . in package names and $ for inner classes),
        //      followed by the byte 0 as a terminator, followed by the API version as a byte.
        for (String clz : classes) {
            buffer.position(nextOffset);
            buffer.putInt(nextEntry);
            nextOffset = buffer.position();
            buffer.position(nextEntry);
            buffer.put(clz.getBytes(Charsets.UTF_8));
            buffer.put((byte) 0);

            ApiClass apiClass = classMap.get(clz);
            assert apiClass != null : clz;
            int since = apiClass.getSince();
            assert since == UnsignedBytes.toInt((byte) since) : since; // make sure it fits
            buffer.put((byte) since);

            nextEntry = buffer.position();
        }

        //  8. Member entry table. Each member entry consists of the class number (as a short),
        //       followed by the JVM method/field signature, encoded as UTF-8, followed by a 0 byte
        //       signature terminator, followed by the API level as a byte.
        assert nextOffset == methodOffsetTable;

        for (int classNumber = 0, n = classes.size(); classNumber < n; classNumber++) {
            String clz = classes.get(classNumber);
            ApiClass apiClass = classMap.get(clz);
            assert apiClass != null : clz;
            List<String> members = memberMap.get(apiClass);
            Collections.sort(members);

            for (String member : members) {
                buffer.position(nextOffset);
                buffer.putInt(nextEntry);
                nextOffset = buffer.position();
                buffer.position(nextEntry);

                Integer since;
                if (member.indexOf('(') != -1) {
                    since = apiClass.getMethod(member, info);
                } else {
                    since = apiClass.getField(member, info);
                }
                if (since == null) {
                    assert false : clz + ':' + member;
                    since = 1;
                }

                assert classNumber == (short) classNumber;
                buffer.putShort((short) classNumber);
                byte[] signature = member.getBytes(Charsets.UTF_8);
                for (int i = 0; i < signature.length; i++) {
                    // Make sure all signatures are really just simple ASCII
                    byte b = signature[i];
                    assert b == (b & 0x7f) : member;
                    buffer.put(b);
                    // Skip types on methods
                    if (b == (byte) ')') {
                        break;
                    }
                }
                buffer.put((byte) 0);
                int api = since;
                assert api == UnsignedBytes.toInt((byte) api);
                //assert api >= 1 && api < 0xFF; // max that fits in a byte
                buffer.put((byte) api);
                nextEntry = buffer.position();
            }
        }

        int size = buffer.position();
        assert size <= buffer.limit();
        buffer.mark();

        if (WRITE_STATS) {
            System.out.println("Wrote " + classes.size() + " classes and "
                    + memberCount + " member entries");
            System.out.print("Actual binary size: " + size + " bytes");
            System.out.println(String.format(" (%.1fM)", size/(1024*1024.f)));

            System.out.println("Allocated size: " + (entryCount * BYTES_PER_ENTRY) + " bytes");
            System.out.println("Required bytes per entry: " + (size/ entryCount) + " bytes");
        }

        // Now dump this out as a file
        // There's probably an API to do this more efficiently; TODO: Look into this.
        byte[] b = new byte[size];
        buffer.rewind();
        buffer.get(b);
        FileOutputStream output = Files.newOutputStreamSupplier(file).getOutput();
        output.write(b);
        output.close();
    }

    // For debugging only
    private String dumpEntry(int offset) {
        if (DEBUG_SEARCH) {
            StringBuilder sb = new StringBuilder();
            for (int i = offset; i < mData.length; i++) {
                if (mData[i] == 0) {
                    break;
                }
                char c = (char) UnsignedBytes.toInt(mData[i]);
                sb.append(c);
            }

            return sb.toString();
        } else {
            return "<disabled>"; //$NON-NLS-1$
        }
    }

    private static int compare(byte[] data, int offset, byte terminator, String s, int max) {
        int i = offset;
        int j = 0;
        for (; j < max; i++, j++) {
            byte b = data[i];
            char c = s.charAt(j);
            // TODO: Check somewhere that the strings are purely in the ASCII range; if not
            // they're not a match in the database
            byte cb = (byte) c;
            int delta = b - cb;
            if (delta != 0) {
                return delta;
            }
        }

        return data[i] - terminator;
    }

    /**
     * Quick determination whether a given class name is possibly interesting; this
     * is a quick package prefix check to determine whether we need to consider
     * the class at all. This let's us do less actual searching for the vast majority
     * of APIs (in libraries, application code etc) that have nothing to do with the
     * APIs in our packages.
     * @param name the class name in VM format (e.g. using / instead of .)
     * @return true if the owner is <b>possibly</b> relevant
     */
    public boolean isRelevantClass(String name) {
        // TODO: Add quick switching here. This is tied to the database file so if
        // we end up with unexpected prefixes there, this could break. For that reason,
        // for now we consider everything relevant.
        return true;
    }

    /**
     * Returns the API version required by the given class reference,
     * or -1 if this is not a known API class. Note that it may return -1
     * for classes introduced in version 1; internally the database only
     * stores version data for version 2 and up.
     *
     * @param className the internal name of the class, e.g. its
     *            fully qualified name (as returned by Class.getName(), but with
     *            '.' replaced by '/'.
     * @return the minimum API version the method is supported for, or -1 if
     *         it's unknown <b>or version 1</b>.
     */
    public int getClassVersion(@NonNull String className) {
        if (!isRelevantClass(className)) {
            return -1;
        }

        if (mData != null) {
            int classNumber = findClass(className);
            if (classNumber != -1) {
                int offset = mIndices[classNumber];
                while (mData[offset] != 0) {
                    offset++;
                }
                offset++;
                return UnsignedBytes.toInt(mData[offset]);
            }
        }  else {
           ApiClass clz = mInfo.getClass(className);
            if (clz != null) {
                int since = clz.getSince();
                if (since == Integer.MAX_VALUE) {
                    since = -1;
                }
                return since;
            }
        }

        return -1;
    }

    /**
     * Returns the API version required by the given method call. The method is
     * referred to by its {@code owner}, {@code name} and {@code desc} fields.
     * If the method is unknown it returns -1. Note that it may return -1 for
     * classes introduced in version 1; internally the database only stores
     * version data for version 2 and up.
     *
     * @param owner the internal name of the method's owner class, e.g. its
     *            fully qualified name (as returned by Class.getName(), but with
     *            '.' replaced by '/'.
     * @param name the method's name
     * @param desc the method's descriptor - see {@link org.objectweb.asm.Type}
     * @return the minimum API version the method is supported for, or -1 if
     *         it's unknown <b>or version 1</b>.
     */
    public int getCallVersion(
            @NonNull String owner,
            @NonNull String name,
            @NonNull String desc) {
        if (!isRelevantClass(owner)) {
            return -1;
        }

        if (mData != null) {
            int classNumber = findClass(owner);
            if (classNumber != -1) {
                return findMember(classNumber, name, desc);
            }
        }  else {
           ApiClass clz = mInfo.getClass(owner);
            if (clz != null) {
                String signature = name + desc;
                int since = clz.getMethod(signature, mInfo);
                if (since == Integer.MAX_VALUE) {
                    since = -1;
                }
                return since;
            }
        }

        return -1;
    }

    /**
     * Returns the API version required to access the given field, or -1 if this
     * is not a known API method. Note that it may return -1 for classes
     * introduced in version 1; internally the database only stores version data
     * for version 2 and up.
     *
     * @param owner the internal name of the method's owner class, e.g. its
     *            fully qualified name (as returned by Class.getName(), but with
     *            '.' replaced by '/'.
     * @param name the method's name
     * @return the minimum API version the method is supported for, or -1 if
     *         it's unknown <b>or version 1</b>
     */
    public int getFieldVersion(
            @NonNull String owner,
            @NonNull String name) {
        if (!isRelevantClass(owner)) {
            return -1;
        }

        if (mData != null) {
            int classNumber = findClass(owner);
            if (classNumber != -1) {
                return findMember(classNumber, name, null);
            }
        }  else {
            ApiClass clz = mInfo.getClass(owner);
            if (clz != null) {
                int since = clz.getField(name, mInfo);
                if (since == Integer.MAX_VALUE) {
                    since = -1;
                }
                return since;
            }
        }

        return -1;
    }

    /** Returns the class number of the given class, or -1 if it is unknown */
    private int findClass(@NonNull String owner) {
        assert owner.indexOf('.') == -1 : "Should use / instead of . in owner: " + owner;

        // The index array contains class indexes from 0 to classCount and
        //   member indices from classCount to mIndices.length.
        int low = 0;
        int high = mClassCount - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int offset = mIndices[middle];

            if (DEBUG_SEARCH) {
                System.out.println("Comparing string " + owner +" with entry at " + offset
                        + ": " + dumpEntry(offset));
            }

            // Compare the api info at the given index.
            int classNameLength = owner.length();
            int compare = compare(mData, offset, (byte) 0, owner, classNameLength);
            if (compare == 0) {
                return middle;
            }

            if (compare < 0) {
                low = middle + 1;
            } else if (compare > 0) {
                high = middle - 1;
            } else {
                assert false; // compare == 0 already handled above
                return -1;
            }
        }

        return -1;
    }

    private int findMember(int classNumber, @NonNull String name, @Nullable String desc) {
        // The index array contains class indexes from 0 to classCount and
        // member indices from classCount to mIndices.length.
        int low = mClassCount;
        int high = mIndices.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int offset = mIndices[middle];

            if (DEBUG_SEARCH) {
                System.out.println("Comparing string " + (name + ';' + desc) +
                        " with entry at " + offset + ": " + dumpEntry(offset));
            }

            // Check class number: read short. The byte data is always big endian.
            int entryClass = (mData[offset++] & 0xFF) << 8 | (mData[offset++] & 0xFF);
            int compare = entryClass - classNumber;
            if (compare == 0) {
                if (desc != null) {
                    // Method
                    int nameLength = name.length();
                    compare = compare(mData, offset, (byte) '(', name, nameLength);
                    if (compare == 0) {
                        offset += nameLength;
                        int argsEnd = desc.indexOf(')');
                        // Only compare up to the ) -- after that we have a return value in the
                        // input description, which isn't there in the database
                        compare = compare(mData, offset, (byte) ')', desc, argsEnd);
                        if (compare == 0) {
                            offset += argsEnd + 1;

                            if (mData[offset++] == 0) {
                                // Yes, terminated argument list: get the API level
                                return UnsignedBytes.toInt(mData[offset]);
                            }
                        }
                    }
                } else {
                    // Field
                    int nameLength = name.length();
                    compare = compare(mData, offset, (byte) 0, name, nameLength);
                    if (compare == 0) {
                        offset += nameLength;
                        if (mData[offset++] == 0) {
                            // Yes, terminated argument list: get the API level
                            return UnsignedBytes.toInt(mData[offset]);
                        }
                    }
                }
            }

            if (compare < 0) {
                low = middle + 1;
            } else if (compare > 0) {
                high = middle - 1;
            } else {
                assert false; // compare == 0 already handled above
                return -1;
            }
        }

        return -1;
    }
}
