import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Makes TeaVM report references to classes/methods/fields that do not exist as
 * warnings instead of errors. TeaVM already replaces such references with code
 * that throws when reached; reporting them as errors only stops it from writing
 * the output. Mods often mention Java features the browser runtime lacks in
 * code paths that never run there (update checks, crash reporters, optional
 * integrations), so this lets them build.
 *
 * Usage: java PatchTeaVM <teavm-core.jar>
 */
public final class PatchTeaVM {
	private static final String TARGET = "org/teavm/dependency/ReferenceResolver.class";

	public static void main(String[] args) throws Exception {
		Path jar = Path.of(args[0]);
		Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
		int patched = 0;
		try (ZipFile in = new ZipFile(jar.toFile());
				ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
			Enumeration<? extends ZipEntry> e = in.entries();
			while (e.hasMoreElements()) {
				ZipEntry entry = e.nextElement();
				byte[] b;
				try (InputStream s = in.getInputStream(entry)) { b = s.readAllBytes(); }
				if (entry.getName().equals(TARGET)) {
					ClassNode cn = new ClassNode();
					new ClassReader(b).accept(cn, 0);
					for (MethodNode m : cn.methods) {
						for (AbstractInsnNode insn : m.instructions) {
							if (insn instanceof MethodInsnNode mi
									&& mi.owner.equals("org/teavm/diagnostics/Diagnostics")
									&& mi.name.equals("error")) {
								mi.name = "warning";
								patched++;
							}
						}
					}
					ClassWriter w = new ClassWriter(0);
					cn.accept(w);
					b = w.toByteArray();
				}
				ZipEntry copy = new ZipEntry(entry.getName());
				copy.setTime(entry.getTime());
				out.putNextEntry(copy);
				out.write(b);
				out.closeEntry();
			}
		}
		if (patched == 0) {
			Files.deleteIfExists(tmp);
			throw new IllegalStateException("no Diagnostics.error calls found in " + TARGET);
		}
		Files.move(tmp, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		System.out.println("PatchTeaVM: " + patched + " missing-reference errors now warnings in " + jar.getFileName());
	}
}
