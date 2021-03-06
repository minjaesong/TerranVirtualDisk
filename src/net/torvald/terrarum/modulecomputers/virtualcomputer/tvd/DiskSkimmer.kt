package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd

import java.io.*
import java.nio.charset.Charset
import java.util.*
import kotlin.collections.HashMap

/**
 * Creates entry-to-offset tables to allow streaming from the disk, without storing whole VD file to the memory.
 *
 * Skimming is only useful for limited applications, reading/adding/removing one or two files occasionally.
 *
 * IO Operation using the skimmer has huge overheads for every operation. For large operations, use VDUtil to load the
 * entire disk onto the memory and modify the disk as much as you want, then export the changes as a new file.
 *
 * Created by minjaesong on 2017-11-17.
 */
class DiskSkimmer(private val diskFile: File, val charset: Charset = Charset.defaultCharset()) {


    /**
     * EntryID to Offset.
     *
     * Offset is where the header begins, so first 4 bytes are exactly the same as the EntryID.
     */
    private var entryToOffsetTable = HashMap<EntryID, Long>()
    private var footerPosition: Long = 0L

    private val footerSize: Int
        get() = (diskFile.length() - footerPosition).toInt()

    /** temporary storage to store tree edges */
    private var directoryStruct = ArrayList<DirectoryEdge>()

    /** root node of the directory tree */
    private var directory = DirectoryNode(0, null, DiskEntry.DIRECTORY, "")

    private data class DirectoryEdge(val nodeParent: EntryID, val node: EntryID, val type: Byte, val name: String)
    private data class DirectoryNode(var nodeThis: EntryID, val nodeParent: EntryID?, var type: Byte, var name: String)

    private val dirDelim = Regex("""[\\/]""")
    private val DIR = "/"

    init {
        println("[DiskSkimmer] loading the diskfile ${diskFile.canonicalPath}")

        val fis = FileInputStream(diskFile)
        var currentPosition = fis.skip(47) // skip disk header


        fun skipRead(bytes: Long) {
            currentPosition += fis.skip(bytes)
        }
        /**
         * Reads a byte and adds up the position var
         */
        fun readByte(): Byte {
            currentPosition++
            val read = fis.read()

            if (read < 0) throw InternalError("Unexpectedly reached EOF")
            return read.toByte()
        }

        /**
         * Reads specific bytes to the buffer and adds up the position var
         */
        fun readBytes(buffer: ByteArray): Int {
            val readStatus = fis.read(buffer)
            currentPosition += readStatus
            return readStatus
        }
        fun readUshortBig(): Int {
            val buffer = ByteArray(2)
            val readStatus = readBytes(buffer)
            if (readStatus != 2) throw InternalError("Unexpected error -- EOF reached? (expected 4, got $readStatus)")
            return buffer.toShortBig()
        }
        fun readIntBig(): Int {
            val buffer = ByteArray(4)
            val readStatus = readBytes(buffer)
            if (readStatus != 4) throw InternalError("Unexpected error -- EOF reached? (expected 4, got $readStatus)")
            return buffer.toIntBig()
        }
        fun readInt48(): Long {
            val buffer = ByteArray(6)
            val readStatus = readBytes(buffer)
            if (readStatus != 6) throw InternalError("Unexpected error -- EOF reached? (expected 6, got $readStatus)")
            return buffer.toInt48()
        }


        while (true) {
            val entryID = readIntBig() // at this point, cursor is 4 bytes past to the entry head

            // footer
            if (entryID == 0xFEFEFEFE.toInt()) {
                footerPosition = currentPosition - 4
                break
            }


            // fill up the offset table
            entryToOffsetTable[entryID] = currentPosition - 4

            val parentID = readIntBig()
            val entryType = readByte()
            val nameBytes = ByteArray(256); readBytes(nameBytes) // read and store to the bytearray

            // fill up the tree's edges table
            directoryStruct.add(DirectoryEdge(parentID, entryID, entryType, nameBytes.toCanonicalString(charset)))

            skipRead(6 + 6 + 4) // skips rest of the header


            // figure out the entry size so that we can skip
            val entrySize: Long = when(entryType) {
                0x01.toByte() -> readInt48()
                0x11.toByte() -> readInt48() + 6 // size of compressed payload + 6 (header elem for uncompressed size)
                0x02.toByte() -> readUshortBig() * 4L
                0x03.toByte() -> 4 // symlink
                else -> throw InternalError("Unknown entry type: ${entryType.toUint()}")
            }


            skipRead(entrySize) // skips rest of the entry's actual contents

            println("[DiskSkimmer] successfully read the entry $entryID (name: ${nameBytes.toCanonicalString(charset)})")
        }


        // construct directory tree from the edges

        /*
         */

        val nodes = HashSet<DirectoryNode>()
        directoryStruct.forEach {
            // TODO
        }
    }

    //////////////////////////////////////////////////
    // THESE ARE METHODS TO SUPPORT ON-LINE READING //
    //////////////////////////////////////////////////

    /**
     * Using entryToOffsetTable, composes DiskEntry on the fly upon request.
     * @return DiskEntry if the entry exists on the disk, `null` otherwise.
     */
    fun requestFile(entryID: EntryID): DiskEntry? {
        entryToOffsetTable[entryID].let { offset ->
            if (offset == null) {
                println("[DiskSkimmer.requestFile] entry $entryID does not exist on the table")
                return null
            }
            else {
                val fis = FileInputStream(diskFile)
                fis.skip(offset + 4) // get to the EntryHeader's parent directory area
                val parent = fis.read(4).toIntBig()
                val fileFlag = fis.read(1)[0]
                val filename = fis.read(256)
                val creationTime = fis.read(6).toInt48()
                val modifyTime = fis.read(6).toInt48()
                val skip_crc = fis.read(4)

                // get entry size     // TODO future me, is this kind of comment helpful or redundant?
                val entrySize = when (fileFlag) {
                    DiskEntry.NORMAL_FILE -> {
                        fis.read(6).toInt48()
                    }
                    DiskEntry.DIRECTORY -> {
                        fis.read(2).toShortBig().toLong()
                    }
                    DiskEntry.SYMLINK -> 4L
                    else -> throw UnsupportedOperationException("Unsupported entry type: $fileFlag") // FIXME no support for compressed file
                }


                val entryContent = when (fileFlag) {
                    DiskEntry.NORMAL_FILE -> {
                        val byteArray = ByteArray64(entrySize)
                        // read one byte at a time
                        for (c in 0L until entrySize) {
                            byteArray[c] = fis.read().toByte()
                        }

                        EntryFile(byteArray)
                    }
                    DiskEntry.DIRECTORY -> {
                        val dirContents = ArrayList<EntryID>()
                        // read 4 bytes at a time
                        val bytesBuffer4 = ByteArray(4)
                        for (c in 0L until entrySize) {
                            fis.read(bytesBuffer4)
                            dirContents.add(bytesBuffer4.toIntBig())
                        }

                        EntryDirectory(dirContents)
                    }
                    DiskEntry.SYMLINK -> {
                        val target = fis.read(4).toIntBig()

                        EntrySymlink(target)
                    }
                    else -> throw UnsupportedOperationException("Unsupported entry type: $fileFlag") // FIXME no support for compressed file
                }

                return DiskEntry(entryID, parent, filename, creationTime, modifyTime, entryContent)
            }
        }
    }

    /**
     * Try to find a file with given path (which uses '/' as a separator). Is search is failed for whatever reason,
     * `null` is returned.
     *
     * @param path A path to the file from the root, directory separated with '/' (and not '\')
     * @return DiskEntry if the search was successful, `null` otherwise
     */
    fun requestFile(path: String): DiskEntry? {
        // fixme pretty much untested

        val path = path.split(dirDelim)
        //println(path)

        // bunch-of-io-access approach (for reading)
        var traversedDir = 0 // entry ID
        var dirFile: DiskEntry? = null
        path.forEachIndexed { index, dirName ->
            println("[DiskSkimmer.requestFile] $index\t$dirName, traversedDir = $traversedDir")

            dirFile = requestFile(traversedDir)
            if (dirFile == null) {
                println("[DiskSkimmer.requestFile] requestFile($traversedDir) came up null")
                return null
            } // outright null
            if (dirFile!!.contents !is EntryDirectory && index < path.lastIndex) { // unexpectedly encountered non-directory
                return null // because other than the last path, everything should be directory (think about it!)
            }
            //if (index == path.lastIndex) return dirFile // reached the end of the search strings

            // still got more paths behind to traverse
            var dirGotcha = false
            // loop for current dir contents
            (dirFile!!.contents as EntryDirectory).forEach {
                if (!dirGotcha) { // alternative impl of 'break' as it's not allowed
                    // get name of the file
                    val childDirFile = requestFile(it)!!
                    if (childDirFile.filename.toCanonicalString(charset) == dirName) {
                        //println("[DiskSkimmer] found, $traversedDir -> $it")
                        dirGotcha = true
                        traversedDir = it
                    }
                }
            }

            if (!dirGotcha) return null // got null || directory empty ||
        }

        return requestFile(traversedDir)
    }

    ///////////////////////////////////////////////////////
    // THESE ARE METHODS TO SUPPORT ON-LINE MODIFICATION //
    ///////////////////////////////////////////////////////

    fun appendEntry(entry: DiskEntry) = appendEntries(listOf(entry))

    fun appendEntries(entries: List<DiskEntry>): Boolean {
        // FIXME untested
        // FIXME dir: files were added but files count was not updated

        // buffer the footer
        // define newFooterPos = 0
        // define newEntryOffsetTable = entryToOffsetTable.clone()
        // make new diskFile_tmp such that:
        //      try :
        //          copy (0 until footerPosition) bytes to the tmpfile -> throws IOException
        //          serialise newly adding entries -> throws IOException
        //          update newEntryOffsetTable
        //          copy (footerPosition until file.length) bytes to the tmpfile -> throws IOException
        //          set newFooterPos
        //      catch IOException:
        //          return false
        // try:
        //      move diskFile to diskFile_old -> throws IOException
        //      move diskFile_tmp to diskFile -> throws IOException
        //      delete diskFile_old -> throws IOException
        // catch IOException:
        //      try:
        //          if (diskFile_old) exists, rename diskFile_old to diskFile -> throws IOException
        //      catch IOException:
        //          do nothing
        //      return false
        // footerPosition = newFooterPos
        // entryToOffsetTable = newEntryOffsetTable
        // return true


        var newFooterPos = 0L
        val originalFile = diskFile.absoluteFile
        val newEntryOffsetTable = entryToOffsetTable.clone() as HashMap<EntryID, Long>
        val tmpFile = File(originalFile.absolutePath + "_tmp")
        val oldFile = File(originalFile.absolutePath + "_old")


        // buffer the footer
        val footerBytes = fetchFooterBytes(diskFile)


        // make tmpfile
        try {
            val tmpOut = BufferedOutputStream(FileOutputStream(tmpFile))
            val oldIn = BufferedInputStream(FileInputStream(diskFile))
            var entryCounter = footerPosition // a counter to set new footer position
            // copy old bytes minus the footer
            byteByByteCopy(footerPosition, oldIn, tmpOut)


            entries.forEach { entry ->
                val bytes = entry.serialize().array

                // update newEntryOffsetTable
                newEntryOffsetTable[entry.entryID] = entryCounter

                // actually copy the bytes
                bytes.forEachBanks { tmpOut.write(it) }

                // update counter
                entryCounter += bytes.size
            }
            tmpOut.write(footerBytes)

            tmpOut.flush(); tmpOut.close()
            oldIn.close()

            // at this point, entryCounter should rightfully point the new footer position
            newFooterPos = entryCounter
        }
        catch (e: Exception) {
            e.printStackTrace()
            return false
        }


        // replace tmpFile with original file
        if (!commitTempfileChange(oldFile, tmpFile)) return false
        // if TRUE is retuned, ignore the return value


        footerPosition = newFooterPos
        entryToOffsetTable = newEntryOffsetTable
        return true

    }

    fun deleteEntry(entry: EntryID) = deleteEntries(listOf(entry))

    fun deleteEntries(entries: List<EntryID>): Boolean {
        // FIXME untested
        // FIXME dir: files were removed but files count was not updated

        // buffer the footer
        // define newFooterPos = 0
        // define newEntryOffsetTable = entryToOffsetTable.clone()
        // make new diskFile_tmp such that:
        //      try :
        //          copy header into the tmpfile -> throws IOException
        //          copy all the surviving entries ONE BY ONE into the tmpfile -> throws IOException
        //          update newEntryOffsetTable
        //          copy (footerPosition until file.length) bytes to the tmpfile -> throws IOException
        //      catch IOException:
        //          return false
        // try:
        //      move diskFile to diskFile_old -> throws IOException
        //      move diskFile_tmp to diskFile -> throws IOException
        //      delete diskFile_old -> throws IOException
        // catch IOException:
        //      try:
        //          if (diskFile_old) exists, rename diskFile_old to diskFile -> throws IOException
        //      catch IOException:
        //          do nothing
        //      return false
        // footerPosition = newFooterPos
        // entryToOffsetTable = newEntryOffsetTable
        // return true

        var newFooterPos = VirtualDisk.HEADER_SIZE
        val originalFile = diskFile.absoluteFile
        val cleanedOffsetTable = entryToOffsetTable.clone() as HashMap<EntryID, Long>
        val newOffsetTable = HashMap<EntryID, Long>()
        val tmpFile = File(originalFile.absolutePath + "_tmp")
        val oldFile = File(originalFile.absolutePath + "_old")

        // buffer the footer
        val footerBytes = fetchFooterBytes(diskFile)


        // make tmpfile
        try {
            val tmpOut = BufferedOutputStream(FileOutputStream(tmpFile))
            val oldIn = BufferedInputStream(FileInputStream(diskFile))

            oldIn.mark(-1) // mark at pos zero

            // copy header
            tmpOut.write(oldIn.read(VirtualDisk.HEADER_SIZE.toInt()))

            // copy entries one by one //

            //// construct iteration offset table that excludes to-be-deleted entries
            entries.forEach { cleanedOffsetTable.remove(it) }

            // copy root entry (the root entry has fixed position on the disk)
            val rootSize = getEntryBlockSize(0)!!
            newOffsetTable[0] = newFooterPos // write in new offset to the offsetTable
            byteByByteCopy(rootSize, oldIn, tmpOut)
            // update footer position
            newFooterPos += rootSize

            // copy non-root entry
            cleanedOffsetTable.forEach { id, offset ->
                if (id != 0) { // remember, the root entry has fixed position!
                    val size = getEntryBlockSize(id)!!
                    newOffsetTable[id] = newFooterPos // write in new offset to the offsetTable
                    oldIn.reset() // return to pos zero
                    oldIn.skip(offset)
                    byteByByteCopy(size, oldIn, tmpOut)

                    // update footer position
                    newFooterPos += size
                }
            }

            // write footer
            tmpOut.write(footerBytes)

            tmpOut.flush()
            tmpOut.close()
            oldIn.close()
        }
        catch (e: Exception) {
            e.printStackTrace()
            return false
        }


        // replace tmpFile with original file
        if (!commitTempfileChange(oldFile, tmpFile)) return false
        // if TRUE is retuned, ignore the return value


        footerPosition = newFooterPos
        entryToOffsetTable = newOffsetTable
        return true
    }

    /**
     * Creates a new file. Nonexisting directories will be automatically constructed.
     * The path cannot contain any symlinks.
     *
     * Also cannot create a directory with this.
     *
     * @return newly created entry's ID
     */
    fun createNewFile(fullPath: String, bytes: ByteArray64, overwriteExisting: Boolean = false): EntryID {
        val path = fullPath.split(dirDelim)

        // figure out number of nonexisting dirs PLUS ONE (for the file itself)
        // the number is equivalent to nonexisting portion of "tails" of the path
        var nonTails = 0 // zero: the file already exists; non-zero: at least the target file does not exist
        var appendParent = 0 // add nonexisting "tails" after this entry; if (nonTails == 0), the value is the conflicting file
        for (i in path.lastIndex downTo 0) { // 0: a random directory inside of root; lastIndex: a file we're going to create
            val searchPath = path.subList(0, i + 1).joinToString(DIR)
            println("[DiskSkimmer.createNewFile] search path: $searchPath")
            val searchResult = requestFile(searchPath)
            if (searchResult != null) {
                appendParent = searchResult.entryID
                break
            }
            else {
                nonTails += 1
            }
        }

        println(appendParent)
        println(nonTails)

        // construct nonexisting entries
        println("[DiskSkimmer.createNewFile] nonTails = $nonTails")
        if (nonTails != 0) {
            // traverse backwards:
            //     file
            //     newdir : file
            //     newdir : newdir : file
            //     (you get the idea)

            var bottomupFile = DiskEntry(
                    generateUniqueID(),
                    if (nonTails == 1) appendParent else generateUniqueID(),
                    path.last().toEntryName(256, charset),
                    VDUtil.currentUnixtime,
                    VDUtil.currentUnixtime,
                    EntryFile(bytes)
            )
            val returnEntryID = bottomupFile.entryID
            val entriesToWrite = ArrayList<DiskEntry>()

            println("[DiskSkimmer.createNewFile] bottomupFile0: $bottomupFile")

            path.takeLast(nonTails).reversed().forEachIndexed { index, s ->
                // a file
                if (index == 0) {
                    /*bottomupFile = DiskEntry(
                            generateUniqueID(),
                            generateUniqueID(),
                            path[path.lastIndex - index].toByteArray(charset),
                            VDUtil.currentUnixtime,
                            VDUtil.currentUnixtime,
                            EntryFile(bytes)
                    )*/ // NOP; already made in the init portion
                }
                // a directory
                else {
                    bottomupFile = DiskEntry(
                            bottomupFile.parentEntryID,
                            // if it's the beginning of the "tail", use appendParent
                            if (index + 1 == nonTails) appendParent else generateUniqueID(),
                            path[path.lastIndex - index].toEntryName(256, charset),
                            VDUtil.currentUnixtime,
                            VDUtil.currentUnixtime,
                            EntryDirectory(arrayListOf(bottomupFile.entryID)) // put child file in (yet overwritten 'bottomupFile')
                    )

                    println("[DiskSkimmer.createNewFile] bottomupFile$index: $bottomupFile")
                }

                entriesToWrite.add(bottomupFile)
            }

            //appendEntries(entriesToWrite) // commented; will write everything in one-go


            // at this point, the link (appendParent) -> (bottomupFile) has not yet constructed
            // (the opposite link should be there)
            // we add this missing link

            val parentFile = requestFile(appendParent)

            if (parentFile == null) {
                throw InternalError()
            }
            else {
                if (parentFile.contents !is EntryDirectory)
                    throw IOException("The disk entry $appendParent (${parentFile.filename.toCanonicalString(charset)}) is not a directory")

                parentFile.contents.add(bottomupFile.entryID)

                // the entry INSTANCE is now updated, now commit the change to the disk file
                // do it by removing old one in the archive then add a new one

                deleteEntry(appendParent)

                //appendEntry(parentFile) // commented; will write everything in one-go
                entriesToWrite.add(parentFile)

                // write everything needed in one-go
                appendEntries(entriesToWrite)
            }


            return returnEntryID
        }
        else {
            if (!overwriteExisting) throw IOException("The file already exists")

            // at this point, 'appendParent' is the conflicting file

            val oldFile = requestFile(appendParent)
            if (oldFile == null) {
                throw InternalError()
            }
            else {

                val newFile = DiskEntry(
                        oldFile.entryID,
                        oldFile.parentEntryID,
                        path.last().toEntryName(256, charset),
                        oldFile.creationDate,
                        VDUtil.currentUnixtime,
                        EntryFile(bytes)
                )

                println("[DiskSkimmer.createNewFile] overwriting file: $newFile")

                // commit change by removing the old and adding the new
                deleteEntry(oldFile.entryID)
                appendEntry(newFile)


                return newFile.entryID
            }
        }
    }

    /**
     * Make sure none of the files are opened by other streams
     */
    fun fixEntryCountUsingActualContents(originalFile: File, tmpFile: File) {
        // scan through every entries for a kind of census
        val censusChild = HashMap<EntryID, Int>() // key: entryID (directory), value: number of childs

        val fis = BufferedInputStream(FileInputStream(originalFile)) // bufferd, in order to use markings
        val fos = FileOutputStream(tmpFile)

        fis.mark(-1) // mark at zero

        // start scanning
        entryToOffsetTable.forEach { t, u ->
            if (t != 0) {
                fis.reset(); fis.skip(u + 4) // skip right into the parentID sect
                val parentID = fis.read(4).toIntBig()

                // append to census results
                if (censusChild.containsKey(parentID)) {
                    censusChild[parentID] = censusChild[parentID]!! + 1
                }
                else {
                    censusChild[parentID] = 1
                }
            }
        }

        // write to temp file
        fis.reset()
        val writePositions = HashMap<Long, Int>()
        censusChild.forEach { censusee ->
            // ignore orphans by ignoring null offsets
            entryToOffsetTable[censusee.key]?.let { offset ->
                writePositions[offset] = censusee.value
            }
        }

        println("fixentry writepositions: $writePositions")

        // key: write position that points to Uint16, value: what to write (in Int)
        var readCtr = -1L // it seems do-while loop shifts thing by one, :\
                         // test this behaviour by writing FF FF to the file.
        do {
            val byte = fis.read(); readCtr += 1

            if (writePositions.containsKey(readCtr)) {
                val newChildCnt = writePositions[readCtr]!!
                val upperByte = newChildCnt.toInt().and(0xF0).ushr(8)
                val lowerByte = newChildCnt.toInt().and(0xF)

                fos.write(upperByte)
                fos.write(lowerByte)

                fis.read(); readCtr += 1 // null read just to increment fis's internal counter by 1
            }
            else {
                fos.write(byte)
            }

        } while (readCtr < originalFile.length() - 1)


        fos.flush()
        fos.close()
        fis.close()
    }



    companion object {
        fun InputStream.read(size: Int): ByteArray {
            val ba = ByteArray(size)
            this.read(ba)
            return ba
        }
    }

    private fun generateUniqueID(): Int {
        var id: Int
        do {
            id = Random().nextInt()
        } while (entryToOffsetTable.containsKey(id) || id == VirtualDisk.FOOTER_MARKER)
        return id
    }

    fun fixEntryCount() {

    }

    private fun commitTempfileChange(oldFile: File, tmpFile: File): Boolean {
        try {
            // fix before actual commit
            //fixEntryCountUsingActualContents(tmpFile, tmpFile)


            // the actual commit
            oldFile.delete()

            // fix commit using tmp2
            val tmpFile2 = File(tmpFile.absolutePath + "2")
            fixEntryCountUsingActualContents(tmpFile, tmpFile2)

            val suc1 = diskFile.renameTo(oldFile)
            if (!suc1) {
                throw RuntimeException("Renaming ${diskFile.canonicalPath} to ${oldFile.canonicalPath} failed")
                return false
            }

            tmpFile2.copyTo(diskFile)

            val suc2 = tmpFile2.delete()
            if (!suc2) {
                throw RuntimeException("Removing tempfile ${tmpFile2.canonicalPath} faild")
                return false
            }

            val suc3 = tmpFile.delete()
            if (!suc3) {
                throw RuntimeException("Removing tempfile ${tmpFile.canonicalPath} faild")
                return false
            }
        }
        catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        return true
    }

    private fun fetchFooterBytes(diskFile: File): ByteArray {
        val fis = FileInputStream(diskFile)
        fis.skip(footerPosition)
        val footerBytes = fis.read(footerSize)
        fis.close()

        return footerBytes
    }

    /**
     * total size of the entry block. This size includes that of the header
     */
    private fun getEntryBlockSize(id: EntryID): Long? {
        val offset = entryToOffsetTable[id] ?: return null

        val HEADER_SIZE = DiskEntry.HEADER_SIZE

        println("[DiskSkimmer.getEntryBlockSize] offset for entry $id = $offset")

        val fis = FileInputStream(diskFile)
        fis.skip(offset + 8)
        val type = fis.read().toByte()
        fis.skip(272) // skip name, timestamp and CRC


        val ret: Long
        when (type) {
            DiskEntry.NORMAL_FILE -> {
                ret = fis.read(6).toInt48() + HEADER_SIZE + 6
            }
            DiskEntry.COMPRESSED_FILE -> {
                ret = fis.read(6).toInt48() + HEADER_SIZE + 12
            }
            DiskEntry.DIRECTORY -> {
                ret = fis.read(2).toShortBig() * 4 + HEADER_SIZE + 2
            }
            DiskEntry.SYMLINK -> { ret = 4 }
            else -> throw UnsupportedOperationException("Unknown type $type for entry $id")
        }

        fis.close()

        return ret
    }

    private fun byteByByteCopy(size: Long, `in`: InputStream, out: OutputStream) {
        for (i in 0L until size) {
            out.write(`in`.read())
        }
    }

    private fun ByteArray.toShortBig(): Int {
        return  this[0].toUint().shl(8) or
                this[1].toUint()
    }

    private fun ByteArray.toIntBig(): Int {
        return  this[0].toUint().shl(24) or
                this[1].toUint().shl(16) or
                this[2].toUint().shl(8) or
                this[3].toUint()
    }

    private fun ByteArray.toInt48(): Long {
        return  this[0].toUlong().shl(40) or
                this[1].toUlong().shl(32) or
                this[2].toUlong().shl(24) or
                this[3].toUlong().shl(16) or
                this[4].toUlong().shl(8) or
                this[5].toUlong()
    }

    private fun ByteArray.toInt64(): Long {
        return  this[0].toUlong().shl(56) or
                this[1].toUlong().shl(48) or
                this[2].toUlong().shl(40) or
                this[3].toUlong().shl(32) or
                this[4].toUlong().shl(24) or
                this[5].toUlong().shl(16) or
                this[6].toUlong().shl(8) or
                this[7].toUlong()
    }
}