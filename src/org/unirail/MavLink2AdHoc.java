package org.unirail;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * MAVLink message-definition XML → AdHoc protocol-description (.cs) converter.
 *
 * <p>Input : a folder with MAVLink dialect XMLs (https://github.com/mavlink/mavlink/tree/master/message_definitions/v1.0).
 * <p>Output: one self-contained <code>&lt;dialect&gt;.cs</code> per top-level XML, ready for AdHocAgent.
 *
 * <p>Usage: <code>java -cp out org.unirail.MavLink2AdHoc &lt;xml folder&gt; [output folder]</code>
 * (output defaults to <code>&lt;cwd&gt;/AdHoc</code>).
 *
 * <p>What the generated descriptor contains, in order:
 * <ol>
 *   <li>Packs Inventory (Dashboard) — every message with its canonical MAVLink id.</li>
 *   <li>One <code>class</code> per <code>&lt;message&gt;</code>; MAVLink field metadata (units, invalid, instance,
 *       display, print_format, default, multiplier, minValue, maxValue, MAVLink-2 extension marker) is carried as
 *       AdHoc custom attributes, which the generator materialises as constants in every target language.</li>
 *   <li>One <code>enum</code> per <code>&lt;enum&gt;</code>; enums split across dialects (MAV_CMD) are merged;
 *       <code>bitmask="true"</code> becomes <code>[Flags]</code>; the underlying type widens to long/ulong when needed.</li>
 *   <li><code>struct &lt;ENUM&gt;_PARAMS</code> constants container with the <code>&lt;param&gt;</code> conventions of
 *       every command entry (MAV_CMD).</li>
 *   <li><code>struct SI_Unit</code> constants container mirrored from mavschema.xsd; <code>[Units(...)]</code>
 *       attributes reference it.</li>
 *   <li>Two demo hosts, one connection with a single bidirectional non-transitional state.</li>
 *   <li>Declarations of the custom attribute classes used above.</li>
 * </ol>
 */
public class MavLink2AdHoc {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("Usage: java -cp out org.unirail.MavLink2AdHoc <folder with MAVLink XML files> [output folder]");
			System.out.println("       output folder defaults to <current dir>/AdHoc");
			return;
		}
		Path src = Paths.get(args[0]);
		Path dst = 1 < args.length ? Paths.get(args[1]) : Paths.get(System.getProperty("user.dir"), "AdHoc");

		File[] files = src.toFile().listFiles((dir, name) -> name.endsWith(".xml"));
		if (files == null || files.length == 0) {
			System.err.println("No .xml files found in `" + src.toAbsolutePath() + "`.");
			System.exit(1);
			return;
		}
		Arrays.sort(files);
		Files.createDirectories(dst);

		int failed = 0;
		for (File file : files)
			try {
				Dialect dialect = Dialect.load(file.toPath());
				Path out = dst.resolve(dialect.name + ".cs");
				Files.write(out, new Emitter(dialect).emit().getBytes(StandardCharsets.UTF_8));
				System.out.printf("%-24s -> %s  (%d messages, %d enums, sources: %s)%n", file.getName(), out, dialect.messages.size(), dialect.enums.size(), String.join(" ", dialect.order));
			} catch (Exception e) {
				failed++;
				System.err.println("FAILED " + file + ": " + e);
				e.printStackTrace();
			}
		if (0 < failed) System.exit(2);
	}

	// ═══════════════════════════════════════════════ model ═══════════════════════════════════════════════

	/** A <code>&lt;deprecated&gt;</code>, <code>&lt;wip&gt;</code> or <code>&lt;superseded&gt;</code> note. */
	static final class Note {
		String kind, since, replacedBy, text;

		String format() {
			StringBuilder sb = new StringBuilder(kind.equals("WIP") ? "🚧 WIP" : "⚠ " + kind);
			if (since != null && !since.isEmpty()) sb.append(" since ").append(since);
			if (replacedBy != null && !replacedBy.isEmpty()) sb.append(", replaced by ").append(replacedBy);
			if (!text.isEmpty()) sb.append(": ").append(text);
			return sb.toString();
		}
	}

	static final class Field {
		String type;                 // MAVLink scalar type: uint8_t, float, char, ...
		int len;                     // array length, 0 for scalars
		String name, enumRef, units, invalid, display, printFormat, dflt, multiplier, minValue, maxValue;
		boolean instance, extension; // extension: declared after the <extensions/> marker (MAVLink 2 only)
		String doc = "";
		final List<Note> notes = new ArrayList<>();
	}

	static final class Message {
		int id;
		String name, origin, doc = "";
		final List<Field> fields = new ArrayList<>();
		final List<Note> notes = new ArrayList<>();
	}

	static final class Param {
		String index, label, units, enumRef, decimalPlaces, increment, minValue, maxValue, dflt, doc = "";
		boolean reserved;
	}

	static final class Entry {
		String name, value, doc = "";
		final List<Note> notes = new ArrayList<>();
		final Map<String, String> flags = new LinkedHashMap<>(); // hasLocation, isDestination, mission, ... (boolean attrs)
		final LinkedHashMap<String, Param> params = new LinkedHashMap<>(); // keyed by index, first occurrence wins
	}

	static final class Enum {
		String name, origin, doc = "";
		boolean bitmask;
		final List<Note> notes = new ArrayList<>();
		final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
	}

	/** A top-level dialect XML together with everything it transitively includes. */
	static final class Dialect {
		String name, version, dialectNo;
		final List<String> order = new ArrayList<>();               // source files in emission order (includes first)
		final LinkedHashMap<String, Message> messages = new LinkedHashMap<>();
		final LinkedHashMap<String, Enum> enums = new LinkedHashMap<>();
		private final Set<String> loaded = new LinkedHashSet<>();

		static Dialect load(Path xml) throws Exception {
			Dialect d = new Dialect();
			String file = xml.getFileName().toString();
			d.name = brush(file.substring(0, file.length() - ".xml".length()));
			d.include(xml);
			return d;
		}

		private void include(Path xml) throws Exception {
			String origin = xml.getFileName().toString();
			if (!loaded.add(origin)) return;

			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setNamespaceAware(false);
			f.setExpandEntityReferences(true);
			Document doc = f.newDocumentBuilder().parse(xml.toFile());
			Element mavlink = doc.getDocumentElement();

			// The top-level file is parsed first, so its <version>/<dialect> take precedence over included ones.
			if (version == null) version = childText(mavlink, "version");
			if (dialectNo == null) dialectNo = childText(mavlink, "dialect");

			for (Element inc : children(mavlink, "include")) {
				Path p = xml.resolveSibling(inc.getTextContent().trim());
				if (Files.exists(p)) include(p);
				else System.err.println("WARNING " + origin + ": included file `" + p + "` not found, skipped.");
			}
			order.add(origin);

			for (Element enums : children(mavlink, "enums"))
				for (Element e : children(enums, "enum")) readEnum(e, origin);

			for (Element messages : children(mavlink, "messages"))
				for (Element m : children(messages, "message")) readMessage(m, origin);
		}

		private void readEnum(Element e, String origin) {
			String name = attr(e, "name");
			Enum en = enums.get(name);
			if (en == null) {
				en = new Enum();
				en.name = name;
				en.origin = origin;
				enums.put(name, en);
			}
			if ("true".equals(attr(e, "bitmask"))) en.bitmask = true;
			String d = childText(e, "description");
			if (en.doc.isEmpty() && d != null) en.doc = d;
			en.notes.addAll(notes(e));

			for (Element ent : children(e, "entry")) {
				Entry x = new Entry();
				x.name = attr(ent, "name");
				x.value = attr(ent, "value");
				String xd = childText(ent, "description");
				if (xd != null) x.doc = xd;
				x.notes.addAll(notes(ent));
				NamedNodeMap attrs = ent.getAttributes();
				for (int i = 0; i < attrs.getLength(); i++) {
					Node a = attrs.item(i);
					String an = a.getNodeName();
					if (!an.equals("name") && !an.equals("value")) x.flags.put(an, a.getNodeValue());
				}
				for (Element p : children(ent, "param")) {
					Param pp = new Param();
					pp.index = attr(p, "index");
					pp.label = attr(p, "label");
					pp.units = attr(p, "units");
					pp.enumRef = attr(p, "enum");
					pp.decimalPlaces = attr(p, "decimalPlaces");
					pp.increment = attr(p, "increment");
					pp.minValue = attr(p, "minValue");
					pp.maxValue = attr(p, "maxValue");
					pp.dflt = attr(p, "default");
					pp.reserved = "true".equals(attr(p, "reserved"));
					pp.doc = ownText(p);
					// Some upstream entries carry two <param> tags with the same index (each documenting an
					// alternative). A nested struct cannot be declared twice, so the first one wins.
					x.params.putIfAbsent(pp.index, pp);
				}
				// An enum may be extended by several dialects (MAV_CMD): the first definition of an entry wins.
				en.entries.putIfAbsent(x.name, x);
			}
		}

		private void readMessage(Element m, String origin) {
			Message msg = new Message();
			msg.id = Integer.parseInt(attr(m, "id"));
			msg.name = attr(m, "name");
			msg.origin = origin;
			boolean extension = false;
			for (Element c : children(m))
				switch (c.getTagName()) {
					case "description": msg.doc = text(c); break;
					case "deprecated":
					case "wip":
					case "superseded": msg.notes.add(note(c)); break;
					case "extensions": extension = true; break;
					case "field":
						Field fld = readField(c);
						fld.extension = extension;
						msg.fields.add(fld);
						break;
					default: break;
				}
			messages.putIfAbsent(msg.name, msg);
		}

		private static Field readField(Element c) {
			Field f = new Field();
			String t = attr(c, "type");
			int br = t.indexOf('[');
			if (0 < br) {
				f.type = t.substring(0, br);
				f.len = Integer.parseInt(t.substring(br + 1, t.indexOf(']')));
			} else f.type = t;
			f.name = attr(c, "name");
			f.enumRef = attr(c, "enum");
			f.units = attr(c, "units");
			f.invalid = attr(c, "invalid");
			f.display = attr(c, "display");
			f.printFormat = attr(c, "print_format");
			f.dflt = attr(c, "default");
			f.multiplier = attr(c, "multiplier");
			f.minValue = attr(c, "minValue");
			f.maxValue = attr(c, "maxValue");
			f.instance = "true".equals(attr(c, "instance"));
			f.doc = ownText(c);
			f.notes.addAll(notes(c));
			return f;
		}
	}

	// ═══════════════════════════════════════════ DOM helpers ═══════════════════════════════════════════

	static List<Element> children(Element e) {
		List<Element> list = new ArrayList<>();
		NodeList nl = e.getChildNodes();
		for (int i = 0; i < nl.getLength(); i++) if (nl.item(i) instanceof Element) list.add((Element) nl.item(i));
		return list;
	}

	static List<Element> children(Element e, String tag) {
		List<Element> list = new ArrayList<>();
		for (Element c : children(e)) if (c.getTagName().equals(tag)) list.add(c);
		return list;
	}

	static String attr(Element e, String name) { return e.hasAttribute(name) ? e.getAttribute(name) : null; }

	static String childText(Element e, String tag) {
		List<Element> c = children(e, tag);
		return c.isEmpty() ? null : text(c.get(0));
	}

	/** Normalised full text content of an element: lines trimmed, blank lines dropped. */
	static String text(Element e) { return normalize(e.getTextContent()); }

	/** Text of the element's own text nodes only (skips nested elements such as &lt;deprecated&gt;). */
	static String ownText(Element e) {
		StringBuilder sb = new StringBuilder();
		NodeList nl = e.getChildNodes();
		for (int i = 0; i < nl.getLength(); i++) {
			Node n = nl.item(i);
			if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) sb.append(n.getNodeValue());
		}
		return normalize(sb.toString());
	}

	static String normalize(String s) {
		if (s == null) return "";
		StringBuilder sb = new StringBuilder();
		for (String line : s.split("\\r?\\n")) {
			line = line.trim();
			if (line.isEmpty()) continue;
			if (0 < sb.length()) sb.append('\n');
			sb.append(line);
		}
		return sb.toString();
	}

	static Note note(Element e) {
		Note n = new Note();
		n.kind = e.getTagName().toUpperCase();
		n.since = attr(e, "since");
		n.replacedBy = attr(e, "replaced_by");
		n.text = text(e);
		return n;
	}

	static List<Note> notes(Element e) {
		List<Note> list = new ArrayList<>();
		for (Element c : children(e))
			switch (c.getTagName()) {
				case "deprecated":
				case "wip":
				case "superseded": list.add(note(c)); break;
				default: break;
			}
		return list;
	}

	// ═══════════════════════════════════════════ emitter ═══════════════════════════════════════════

	static final class Emitter {
		static final int DOC_WIDTH = 110;
		static final String I1 = "    ", I2 = I1 + I1, I3 = I2 + I1, I4 = I3 + I1, I5 = I4 + I1;

		final Dialect d;
		final StringBuilder sb = new StringBuilder(1 << 20);

		/**
		 * MAVLink fields that are unambiguously an elapsed time since system boot, mapped to the name of the
		 * AdHoc {@code Duration} alias they take. Only names whose meaning MAVLink fixes are listed: a field
		 * the specification leaves ambiguous (notably {@code time_usec}) stays a plain integer.
		 */
		static final Map<String, String> ELAPSED_ALIAS = new LinkedHashMap<>();

		static {
			ELAPSED_ALIAS.put("time_boot_ms", "MillisecondsSinceBoot");
			ELAPSED_ALIAS.put("time_boot_us", "MicrosecondsSinceBoot");
		}

		/** Aliases actually referenced by this dialect, so only those are declared. */
		final Set<String> usedElapsed = new LinkedHashSet<>();

		Emitter(Dialect d) { this.d = d; }

		String emit() {
			sb.append("// Generated by MavLink2AdHoc (https://github.com/AdHoc-Protocol) from ").append(d.order.get(d.order.size() - 1)).append('\n');
			sb.append("// Sources, in emission order: ").append(String.join(", ", d.order)).append('\n');
			if (d.version != null) sb.append("// MAVLink protocol version: ").append(d.version).append('\n');
			if (d.dialectNo != null) sb.append("// MAVLink dialect number: ").append(d.dialectNo).append('\n');
			sb.append("// Re-run the converter instead of editing this file by hand.\n\n");
			sb.append("using System;\n");
			sb.append("using org.unirail.Meta;\n\n");
			sb.append("namespace org.mavlink {\n");

			// Messages are rendered first because emitting them is what discovers which Duration aliases the
			// dialect needs; the finished text is assembled in reading order below.
			StringBuilder body = new StringBuilder();
			messages(body);

			dashboard();
			sb.append(I1).append("public interface ").append(d.name).append(" {\n");
			durationAliases();
			sb.append(body);
			enums();
			paramsContainers();
			siUnit();
			topology();
			attributeDeclarations();
			sb.append(I1).append("}\n");
			sb.append("}\n");
			return sb.toString();
		}

		// ───────────────────────────── Packs Inventory (Dashboard) ─────────────────────────────

		void dashboard() {
			// The Packs Inventory: an alphabetised menu of every message, with NO `id` attribute.
			// A pack id is AdHoc's own internal matter - the agent assigns and maintains it. The canonical
			// MAVLink message number is source metadata and lives inside the pack as `const int message_id`.
			// Pinning it here would claim a wire compatibility that does not exist: AdHoc lays out its own
			// frame, so a descriptor generated from MAVLink is a different protocol carrying the same data.
			TreeMap<String, String> names = new TreeMap<>();
			for (Message m : d.messages.values()) names.put(brush(m.name), "");
			sb.append(I1).append("/**\n");
			for (String n : names.keySet()) sb.append(I2).append("<see cref = '").append(n).append("'/>\n");
			sb.append(I1).append("*/\n");
		}

		// ───────────────────────────── messages → packs ─────────────────────────────

		void messages(StringBuilder out) {
			String origin = null;
			for (Message m : d.messages.values()) {
				if (!m.origin.equals(origin)) {
					origin = m.origin;
					out.append('\n').append(I2).append("// ═════════════════════════ messages of ").append(origin).append(" ═════════════════════════\n");
				}
				out.append('\n');
				doc(out, I2, m.doc, m.notes);
				out.append(I2).append("class ").append(brush(m.name)).append(" {\n");
				// The MAVLink message number is source metadata, not an AdHoc pack id: it is kept here so a
				// migration can be audited and a gateway can map both ways, while AdHoc numbers its own packs.
				// C# forbids two members of one class sharing a name, and MESSAGE_INTERVAL really does carry a
				// field called `message_id`, so the constant steps aside when the message already uses the name.
				Set<String> taken = new LinkedHashSet<>();
				for (Field f : m.fields) taken.add(brush(f.name));
				String idConst = "mavlink_message_id";
				for (int i = 2; taken.contains(idConst); i++) idConst = "mavlink_message_id" + i;
				out.append(I3).append("public const int ").append(idConst).append(" = ").append(m.id).append(";\n");
				for (Field f : m.fields) {
					doc(out, I3, f.doc, f.notes);
					out.append(I3).append(field(f)).append('\n');
				}
				out.append(I2).append("}\n");
			}
		}

		/**
		 * Declares the {@code Duration} aliases this dialect actually uses. AdHoc sizes the field from
		 * {@code max} and {@code precision}, so the generated API hands the application a time value rather
		 * than a raw counter, and the wire carries only the bytes the range needs.
		 */
		void durationAliases() {
			if (usedElapsed.isEmpty()) return;
			sb.append('\n').append(I2).append("// ═════════════════════════ elapsed-time aliases ═════════════════════════\n\n");
			if (usedElapsed.contains("MillisecondsSinceBoot")) {
				sb.append(I2).append("/** Time since system boot, millisecond resolution. MAVLink carries it as a uint32, which wraps after about 49.7 days. */\n");
				sb.append(I2).append("class MillisecondsSinceBoot : Duration {\n");
				sb.append(I3).append("public long     max       => uint.MaxValue;\n");
				sb.append(I3).append("public TimeSpan precision => TimeSpan.FromMilliseconds(1);\n");
				sb.append(I2).append("}\n\n");
			}
			if (usedElapsed.contains("MicrosecondsSinceBoot")) {
				sb.append(I2).append("/** Time since system boot, microsecond resolution. */\n");
				sb.append(I2).append("class MicrosecondsSinceBoot : Duration {\n");
				sb.append(I3).append("public long     max       => uint.MaxValue;\n");
				sb.append(I3).append("public TimeSpan precision => TimeSpan.FromMicroseconds(1);\n");
				sb.append(I2).append("}\n\n");
			}
		}

		String field(Field f) {
			List<String> attrs = new ArrayList<>();
			boolean string = f.type.equals("char") && 0 < f.len; // char[N]: null-padded fixed buffer used as a string
			if (string) attrs.add("D(+" + f.len + ")");
			else if (0 < f.len) attrs.add("D(" + f.len + ")");

			if (f.units != null) attrs.add("Units(" + unitRef(f.units) + ")");
			if (f.invalid != null) attrs.add("Invalid(" + str(f.invalid) + ")");
			if (f.instance) attrs.add("Instance");
			if (f.display != null) attrs.add("Display(" + str(f.display) + ")");
			if (f.printFormat != null) attrs.add("PrintFormat(" + str(f.printFormat) + ")");
			if (f.dflt != null) attrs.add("Default(" + str(f.dflt) + ")");
			if (f.multiplier != null) attrs.add("Multiplier(" + str(f.multiplier) + ")");
			if (f.minValue != null) attrs.add("MinValue(" + num(f.minValue) + ")");
			if (f.maxValue != null) attrs.add("MaxValue(" + num(f.maxValue) + ")");
			if (f.extension) attrs.add("ExtensionField");

			String type;
			String comment = "";
			// Time since system boot is an elapsed duration, and AdHoc models that natively: the field takes a
			// `Duration` alias instead of a bare integer, so the generated API speaks time, not milliseconds.
			// `time_usec` deliberately stays a plain integer - MAVLink defines it as EITHER a UNIX epoch stamp
			// or time since boot, decided by the receiver from its magnitude, so calling it a DateTime would lie.
			String elapsed = f.len == 0 ? ELAPSED_ALIAS.get(f.name) : null;
			if (elapsed != null) usedElapsed.add(elapsed);

			if (string) type = "string";
			else if (elapsed != null) type = elapsed;
			else {
				type = csType(f.type);
				if (f.enumRef != null) {
					Enum en = d.enums.get(f.enumRef);
					if (en == null) comment = " // enum " + f.enumRef + " is not defined in this dialect";
					else if (en.entries.size() < 2) comment = " // values: constants container " + brush(f.enumRef);
					else type = brush(f.enumRef);
				}
				if (0 < f.len) type += "[]";
			}
			String decl = (attrs.isEmpty() ? "" : "[" + String.join(", ", attrs) + "] ") + type + " " + brush(f.name) + ";" + comment;
			String hint = physics(f, type);
			return hint == null ? decl : hint + '\n' + I3 + decl;
		}

		/**
		 * A note about the field's physics, or null when MAVLink says nothing usable.
		 *
		 * <p>Whether a number should be varint-encoded is decided by where its values sit, never by how MAVLink
		 * packs its own frame - AdHoc lays out its own packet. MAVLink states units and names but no
		 * distribution, so the converter does not choose; it writes down what the units imply and leaves the
		 * decision on the field, where the person who knows the data will be reading.
		 *
		 * <p>Only integers wider than one byte are worth a note: a byte has no leading groups to drop, and the
		 * attributes do not apply to floats at all.
		 */
		static String physics(Field f, String type) {
			// An array, an enum-typed field, or a field the converter already lifted into a Duration alias needs no note.
			if (0 < f.len || f.enumRef != null || ELAPSED_ALIAS.containsKey(f.name)) return null;
			boolean wide = switch (f.type) {
				case "uint16_t", "int16_t", "uint32_t", "int32_t", "uint64_t", "int64_t" -> true;
				default -> false;
			};
			if (!wide) return null;
			boolean big32 = f.type.startsWith("uint32") || f.type.startsWith("int32")
			                || f.type.startsWith("uint64") || f.type.startsWith("int64");
			String u = f.units == null ? "" : f.units;
			String n = f.name.toLowerCase();

			// Systematically large values: varint always loses past 268 435 455, so say so rather than stay silent.
			if (u.equals("degE7") || u.equals("degE5"))
				return "// physics: scaled degrees, values around 5.6e8 - a varint attribute would COST a byte here";
			if (n.startsWith("time_") || n.endsWith("_utc") || u.equals("us") && n.contains("time"))
				return "// physics: monotonic timestamp, systematically large - a varint attribute would COST a byte here";

			// Two-sided quantities centred on zero.
			if (u.equals("rad/s") || u.equals("mrad/s") || u.equals("deg/s") || u.equals("cdeg/s") || u.equals("ddeg/s")
			    || u.equals("rad") || u.equals("m/s") || u.equals("cm/s") || u.equals("dm/s") || u.equals("mm/s")
			    || u.equals("m/s/s") || u.equals("mG") || u.equals("mT") || u.equals("mgauss") || u.equals("gauss"))
				return "// physics: rate or vector component, centred on zero" + (big32 ? " - consider [X]" : " - [X] would fit, though the type is already narrow");
			if (u.equals("degC") || u.equals("cdegC") || u.equals("K"))
				return "// physics: temperature, clusters around ambient" + (big32 ? " - consider [X]" : " - [X] would fit, though the type is already narrow");

			// One-sided quantities floored at zero.
			if (n.endsWith("_acc") || n.contains("accuracy") || n.endsWith("_count") || n.endsWith("_cnt")
			    || n.equals("count") || n.endsWith("_num") || n.contains("seq"))
				return "// physics: floored at zero, values typically small" + (big32 ? " - consider [A]" : " - [A] would fit, though the type is already narrow");
			if (big32 && (u.equals("mm") || u.equals("cm") || u.equals("mV") || u.equals("mA") || u.equals("mAh")))
				return "// physics: small magnitude in a 32-bit field - consider [A] if never negative, [X] if it is";
			return null;
		}

		// ───────────────────────────── enums ─────────────────────────────

		void enums() {
			sb.append('\n').append(I2).append("// ═════════════════════════ enums ═════════════════════════\n");
			for (Enum en : d.enums.values()) {
				sb.append('\n');
				if (en.entries.size() < 2) {
					constantsContainer(en);
					continue;
				}
				doc(I2, en.doc, en.notes);
				if (en.bitmask) sb.append(I2).append("[Flags]\n");
				sb.append(I2).append("enum ").append(brush(en.name)).append(underlying(en)).append(" {\n");
				for (Entry e : en.entries.values()) {
					doc(I3, e.doc, e.notes);
					sb.append(I3).append(brush(e.name));
					if (e.value != null) sb.append(" = ").append(e.value);
					sb.append(",\n");
				}
				sb.append(I2).append("}\n");
			}
		}

		/**
		 * AdHoc rejects an enum with fewer than two constants (a single possible value carries no information), so
		 * such MAVLink enums are kept as a non-transmittable constants container; fields that reference them stay
		 * primitive.
		 */
		void constantsContainer(Enum en) {
			sb.append(I2).append("// MAVLink enum with fewer than two entries: AdHoc rejects such enums, kept as a constants container.\n");
			doc(I2, en.doc, en.notes);
			sb.append(I2).append("public struct ").append(brush(en.name)).append(" {\n");
			for (Entry e : en.entries.values()) {
				doc(I3, e.doc, e.notes);
				sb.append(I3).append("public const long ").append(brush(e.name)).append(" = ").append(e.value == null ? "0" : e.value).append(";\n");
			}
			if (en.entries.isEmpty()) sb.append(I3).append("public const bool EMPTY = true; // the MAVLink enum declares no entries\n");
			sb.append(I2).append("}\n");
		}

		/** Underlying type suffix: none (int) unless some value does not fit a 32-bit int. */
		static String underlying(Enum en) {
			boolean needLong = false;
			for (Entry e : en.entries.values()) {
				if (e.value == null) continue;
				try {
					long v = Long.decode(e.value);
					if (v < Integer.MIN_VALUE || Integer.MAX_VALUE < v) needLong = true;
				} catch (NumberFormatException ex) {
					try { // beyond long.MaxValue — only representable as ulong
						String v = e.value.trim();
						if (v.startsWith("0x") || v.startsWith("0X")) Long.parseUnsignedLong(v.substring(2), 16);
						else Long.parseUnsignedLong(v);
						return " : ulong";
					} catch (NumberFormatException ex2) {
						System.err.println("WARNING enum " + en.name + "." + e.name + ": cannot parse value `" + e.value + "`");
					}
				}
			}
			return needLong ? " : long" : "";
		}

		// ───────────────────────────── <param> conventions (MAV_CMD) ─────────────────────────────

		void paramsContainers() {
			for (Enum en : d.enums.values()) {
				boolean any = false;
				for (Entry e : en.entries.values()) if (!e.params.isEmpty() || !e.flags.isEmpty()) { any = true; break; }
				if (!any) continue;

				String name = brush(en.name) + "_PARAMS";
				sb.append('\n').append(I2).append("// ═════════════════════════ ").append(name).append(" ═════════════════════════\n\n");
				sb.append(I2).append("/**\n");
				sb.append(I3).append("Non-transmittable constants container: per-command parameter conventions of ").append(brush(en.name)).append(".\n");
				sb.append(I3).append("Each nested struct is named after the command; param_N holds the MAVLink &lt;param&gt; metadata.\n");
				sb.append(I2).append("*/\n");
				sb.append(I2).append("public struct ").append(name).append(" {\n");
				for (Entry e : en.entries.values()) {
					if (e.params.isEmpty() && e.flags.isEmpty()) continue;
					sb.append(I3).append("public struct ").append(brush(e.name)).append(" {\n");
					if (e.value != null) sb.append(I4).append("public const long value = ").append(e.value).append(";\n");
					for (Map.Entry<String, String> fl : e.flags.entrySet()) {
						String v = fl.getValue();
						if (v.equals("true") || v.equals("false")) sb.append(I4).append("public const bool ").append(brush(fl.getKey())).append(" = ").append(v).append(";\n");
						else sb.append(I4).append("public const string ").append(brush(fl.getKey())).append(" = ").append(str(v)).append(";\n");
					}
					for (Param p : e.params.values()) {
						sb.append(I4).append("public struct param_").append(p.index).append(" {\n");
						sb.append(I5).append("public const int index = ").append(p.index).append(";\n");
						if (p.label != null) sb.append(I5).append("public const string label = ").append(str(p.label)).append(";\n");
						if (p.units != null) sb.append(I5).append("public const string units = ").append(unitRef(p.units)).append(";\n");
						if (p.enumRef != null) sb.append(I5).append("public const string Enum = ").append(str(p.enumRef)).append(";\n");
						if (p.decimalPlaces != null) sb.append(I5).append("public const ").append(numConst("decimalPlaces", p.decimalPlaces)).append(";\n");
						if (p.increment != null) sb.append(I5).append("public const ").append(numConst("increment", p.increment)).append(";\n");
						if (p.minValue != null) sb.append(I5).append("public const ").append(numConst("minValue", p.minValue)).append(";\n");
						if (p.maxValue != null) sb.append(I5).append("public const ").append(numConst("maxValue", p.maxValue)).append(";\n");
						if (p.reserved) sb.append(I5).append("public const bool reserved = true;\n");
						if (p.dflt != null) sb.append(I5).append("public const string Default = ").append(str(p.dflt)).append(";\n");
						if (!p.doc.isEmpty()) sb.append(I5).append("public const string description = ").append(verbatim(p.doc)).append(";\n");
						sb.append(I4).append("}\n");
					}
					sb.append(I3).append("}\n");
				}
				sb.append(I2).append("}\n");
			}
		}

		// ───────────────────────────── SI units ─────────────────────────────

		void siUnit() {
			sb.append('\n').append(I2).append("// ═════════════════════════ SI units (mirrored from mavschema.xsd) ═════════════════════════\n\n");
			sb.append(I2).append("/** Non-transmittable constants container with every unit string MAVLink allows; [Units] attributes reference it. */\n");
			sb.append(I2).append("public struct SI_Unit {\n");
			String group = null;
			int width = 0;
			for (String[] u : SI_UNITS) width = Math.max(width, u[1].length());
			for (String[] u : SI_UNITS) {
				if (!u[0].equals(group)) {
					if (group != null) sb.append(I3).append("}\n\n");
					group = u[0];
					sb.append(I3).append("public struct ").append(group).append(" {\n");
				}
				sb.append(I4).append("public const string ").append(u[1]).append(pad(width - u[1].length())).append(" = ").append(str(u[2])).append("; // ").append(u[3]).append('\n');
			}
			sb.append(I3).append("}\n");
			sb.append(I2).append("}\n");
		}

		/** Attribute/constant argument for a MAVLink unit: a SI_Unit reference when known, else a string literal. */
		static String unitRef(String unit) {
			String ref = UNIT_REF.get(unit);
			return ref != null ? ref : str(unit);
		}

		// ───────────────────────────── hosts and connection ─────────────────────────────

		void topology() {
			sb.append('\n').append(I2).append("// ═════════════════════════ demo topology ═════════════════════════\n\n");
			sb.append(I2).append("// MAVLink defines no network topology (packets only carry system_id / component_id), so the demo\n");
			sb.append(I2).append("// invents two hosts and lets either side send any message without changing state.\n\n");
			hostLangs();
			sb.append(I2).append("struct GroundControl : Host { }\n\n");
			hostLangs();
			sb.append(I2).append("struct MicroAirVehicle : Host { }\n\n");
			sb.append(I2).append("interface CommunicationChannel : Connects<GroundControl, MicroAirVehicle> {\n");
			sb.append(I3).append("// Every message class of the dialect, in both directions; the FSM never transitions.\n");
			sb.append(I3).append("// The recursive @scope would also collect the metadata attribute classes declared at the end of\n");
			sb.append(I3).append("// this interface, so they are filtered out by name.\n");
			sb.append(I3).append("// Replace with explicit l____________ / ____________r branches to route messages per host.\n");
			sb.append(I3).append("[_____lr_____<@").append(d.name).append(">(SkipName: @\"Attribute$\")]\n");
			sb.append(I3).append("struct Start { }\n");
			sb.append(I2).append("}\n");
		}

		void hostLangs() {
			sb.append(I2).append("/**\n");
			for (String l : new String[]{"InTS", "InJAVA", "InCS", "InCPP", "InGO", "InRS"})
				sb.append(I2).append("<see cref = '").append(l).append("'/>\n");
			sb.append(I2).append("*/\n");
		}

		// ───────────────────────────── custom attribute declarations ─────────────────────────────

		void attributeDeclarations() {
			sb.append('\n').append(I2).append("// ═════════════════════════ MAVLink metadata attributes ═════════════════════════\n\n");
			sb.append(I2).append("// AdHoc custom attributes: the generator carries each one into the generated code as constants\n");
			sb.append(I2).append("// attached to the field, so units, invalid markers, display hints etc. stay available at runtime.\n");
			sb.append(I2).append("// They must live inside the project interface (the agent expects every class in a project); the\n");
			sb.append(I2).append("// connection's branch excludes them from its pack scope with SkipName.\n\n");
			sb.append(I2).append("/** SI unit of a numeric field (a SI_Unit constant). */\n");
			sb.append(I2).append("public class UnitsAttribute : Attribute { public UnitsAttribute(string units) { } }\n\n");
			sb.append(I2).append("/** Value (or, for arrays, the [value] / [value:] / [v1,,v3,] notation) that marks the field as not set. */\n");
			sb.append(I2).append("public class InvalidAttribute : Attribute { public InvalidAttribute(string value) { } }\n\n");
			sb.append(I2).append("/** The field identifies the sensor / battery instance the message is about. */\n");
			sb.append(I2).append("public class InstanceAttribute : Attribute { }\n\n");
			sb.append(I2).append("/** UI hint, e.g. \"bitmask\": show the enum values as check boxes. */\n");
			sb.append(I2).append("public class DisplayAttribute : Attribute { public DisplayAttribute(string hint) { } }\n\n");
			sb.append(I2).append("/** printf-style display format. */\n");
			sb.append(I2).append("public class PrintFormatAttribute : Attribute { public PrintFormatAttribute(string format) { } }\n\n");
			sb.append(I2).append("/** Default value of the field. */\n");
			sb.append(I2).append("public class DefaultAttribute : Attribute { public DefaultAttribute(string value) { } }\n\n");
			sb.append(I2).append("/** Scale factor: actual value = stated value * multiplier. */\n");
			sb.append(I2).append("public class MultiplierAttribute : Attribute { public MultiplierAttribute(string factor) { } }\n\n");
			sb.append(I2).append("/** Minimum value hint for UIs (not a wire constraint: [Invalid] markers may lie outside it). */\n");
			sb.append(I2).append("public class MinValueAttribute : Attribute { public MinValueAttribute(double value) { } public MinValueAttribute(string value) { } }\n\n");
			sb.append(I2).append("/** Maximum value hint for UIs (not a wire constraint: [Invalid] markers may lie outside it). */\n");
			sb.append(I2).append("public class MaxValueAttribute : Attribute { public MaxValueAttribute(double value) { } public MaxValueAttribute(string value) { } }\n\n");
			sb.append(I2).append("/** Declared after the &lt;extensions/&gt; marker: present in MAVLink 2 frames only. */\n");
			sb.append(I2).append("public class ExtensionFieldAttribute : Attribute { }\n");
		}

		// ───────────────────────────── text helpers ─────────────────────────────

		/** Emits a doc comment (if there is anything to say). Text is XML-escaped: AdHocAgent parses docs as XML. */
		void doc(String indent, String text, List<Note> notes) { doc(sb, indent, text, notes); }

		static void doc(StringBuilder out, String indent, String text, List<Note> notes) {
			List<String> lines = new ArrayList<>();
			if (text != null && !text.isEmpty()) for (String l : text.split("\n")) wrap(l, lines);
			for (Note n : notes) wrap(n.format(), lines);
			if (lines.isEmpty()) return;
			out.append(indent).append("/**\n");
			for (String l : lines) out.append(indent).append(l).append('\n');
			out.append(indent).append("*/\n");
		}

		static void wrap(String line, List<String> out) {
			line = xmlEscape(line);
			while (DOC_WIDTH < line.length()) {
				int cut = line.lastIndexOf(' ', DOC_WIDTH);
				if (cut <= 0) cut = line.indexOf(' ', DOC_WIDTH);
				if (cut <= 0) break;
				out.add(line.substring(0, cut));
				line = line.substring(cut + 1);
			}
			out.add(line);
		}

		static String xmlEscape(String s) {
			return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
					.replace("*/", "*&#47;").replace("/*", "&#47;*"); // never terminate / start a comment from inside a doc
		}

		static String str(String s) {
			return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n") + "\"";
		}

		static String verbatim(String s) { return "@\"" + s.replace("\"", "\"\"") + "\""; }

		/** A numeric C# literal when the value parses as a finite number, otherwise a string literal. */
		static String num(String s) {
			try {
				double v = Double.parseDouble(s.trim());
				if (Double.isNaN(v) || Double.isInfinite(v)) return str(s);
				if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
				return Double.toString(v);
			} catch (NumberFormatException e) { return str(s); }
		}

		static String numConst(String name, String value) {
			String lit = num(value);
			return (lit.startsWith("\"") ? "string " : lit.contains(".") || lit.contains("E") ? "double " : "long ") + name + " = " + lit;
		}

		static String pad(int n) {
			StringBuilder p = new StringBuilder();
			for (int i = 0; i < n; i++) p.append(' ');
			return p.toString();
		}
	}

	// ═══════════════════════════════════════════ type mapping ═══════════════════════════════════════════

	/** MAVLink scalar → AdHoc/C# type. `uint8_t_mavlink_version` is the auto-injected protocol version byte. */
	static String csType(String t) {
		switch (t) {
			case "uint8_t":
			case "uint8_t_mavlink_version": return "byte";
			case "int8_t": return "sbyte";
			case "uint16_t": return "ushort";
			case "int16_t": return "short";
			case "uint32_t": return "uint";
			case "int32_t": return "int";
			case "uint64_t": return "ulong";
			case "int64_t": return "long";
			case "float": return "float";
			case "double": return "double";
			case "char": return "char";
			default:
				System.err.println("WARNING unknown MAVLink type `" + t + "`, passed through unchanged");
				return t;
		}
	}

	// ═══════════════════════════════════════════ SI units table ═══════════════════════════════════════════

	// {group, constant name, MAVLink unit string, comment} — https://github.com/ArduPilot/pymavlink/blob/master/generator/mavschema.xsd
	static final String[][] SI_UNITS = {
			{"time", "s", "s", "seconds"},
			{"time", "ds", "ds", "deciseconds"},
			{"time", "cs", "cs", "centiseconds"},
			{"time", "ms", "ms", "milliseconds"},
			{"time", "us", "us", "microseconds"},
			{"time", "ns", "ns", "nanoseconds"},
			{"time", "Hz", "Hz", "Herz"},
			{"time", "MHz", "MHz", "Mega-Herz"},
			{"distance", "km", "km", "kilometres"},
			{"distance", "dam", "dam", "decametres"},
			{"distance", "m", "m", "metres"},
			{"distance", "m_2", "m^2", "metres squared (typically used in variance)"},
			{"distance", "m_s", "m/s", "metres per second"},
			{"distance", "m_s_s", "m/s/s", "metres per second per second"},
			{"distance", "m_s_5", "m/s*5", "metres per second * 5 required from dagar for HIGH_LATENCY2 message"},
			{"distance", "dm", "dm", "decimetres"},
			{"distance", "dm_s", "dm/s", "decimetres per second"},
			{"distance", "cm", "cm", "centimetres"},
			{"distance", "cm_2", "cm^2", "centimetres squared (typically used in variance)"},
			{"distance", "cm_s", "cm/s", "centimetres per second"},
			{"distance", "mm", "mm", "millimetres"},
			{"distance", "mm_s", "mm/s", "millimetres per second"},
			{"distance", "mm_h", "mm/h", "millimetres per hour"},
			{"temperature", "K", "K", "Kelvin"},
			{"temperature", "degC", "degC", "degrees Celsius"},
			{"temperature", "cdegC", "cdegC", "centi degrees Celsius"},
			{"angle", "rad", "rad", "radians"},
			{"angle", "rad_s", "rad/s", "radians per second"},
			{"angle", "mrad_s", "mrad/s", "milli-radians per second"},
			{"angle", "deg", "deg", "degrees"},
			{"angle", "deg_2", "deg/2", "degrees/2 required from dagar for HIGH_LATENCY2 message"},
			{"angle", "ddeg_s", "ddeg/s", "decidegrees per second (AIS_VESSEL only, not recommended elsewhere)"},
			{"angle", "deg_s", "deg/s", "degrees per second"},
			{"angle", "cdeg", "cdeg", "centidegrees"},
			{"angle", "cdeg_s", "cdeg/s", "centidegrees per second"},
			{"angle", "degE5", "degE5", "degrees * 1E5"},
			{"angle", "degE7", "degE7", "degrees * 1E7"},
			{"angle", "rpm", "rpm", "rotations per minute"},
			{"electricity", "V", "V", "Volt"},
			{"electricity", "cV", "cV", "centi-Volt"},
			{"electricity", "mV", "mV", "milli-Volt"},
			{"electricity", "A", "A", "Ampere"},
			{"electricity", "cA", "cA", "centi-Ampere"},
			{"electricity", "mA", "mA", "milli-Ampere"},
			{"electricity", "mAh", "mAh", "milli-Ampere hour"},
			{"electricity", "Ah", "Ah", "Ampere hour"},
			{"magnetism", "mT", "mT", "milli-Tesla"},
			{"magnetism", "gauss", "gauss", "Gauss"},
			{"magnetism", "mgauss", "mgauss", "milli-Gauss"},
			{"energy", "hJ", "hJ", "hecto-Joule"},
			{"power", "W", "W", "Watt"},
			{"force", "mG", "mG", "milli-G"},
			{"mass", "g", "g", "grams"},
			{"mass", "kg", "kg", "kilograms"},
			{"pressure", "Pa", "Pa", "Pascal"},
			{"pressure", "hPa", "hPa", "hecto-Pascal"},
			{"pressure", "kPa", "kPa", "kilo-Pascal"},
			{"pressure", "mbar", "mbar", "millibar"},
			{"ratio", "percent", "%", "percent"},
			{"ratio", "decipercent", "d%", "decipercent"},
			{"ratio", "centipercent", "c%", "centipercent"},
			{"ratio", "dB", "dB", "Deci-Bell"},
			{"ratio", "dBm", "dBm", "Deci-Bell-milliwatts"},
			{"digital", "KiB", "KiB", "Kibibyte (1024 bytes)"},
			{"digital", "KiB_s", "KiB/s", "Kibibyte (1024 bytes) per second"},
			{"digital", "MiB", "MiB", "Mebibyte (1024*1024 bytes)"},
			{"digital", "MiB_s", "MiB/s", "Mebibyte (1024*1024 bytes) per second"},
			{"digital", "bytes", "bytes", "bytes"},
			{"digital", "bytes_s", "bytes/s", "bytes per second"},
			{"digital", "bits_s", "bits/s", "bits per second"},
			{"digital", "pix", "pix", "pixels"},
			{"digital", "dpix", "dpix", "decipixels"},
			{"flow", "g_min", "g/min", "grams/minute"},
			{"flow", "cm_3_min", "cm^3/min", "cubic centimetres/minute"},
			{"flow", "L_h", "L/h", "Litres/hour"},
			{"volume", "cm_3", "cm^3", "cubic centimetres"},
			{"volume", "l", "l", "litres"},
			{"volume", "L", "L", "Litres"},
	};

	static final Map<String, String> UNIT_REF = new HashMap<>();

	static {
		for (String[] u : SI_UNITS) UNIT_REF.put(u[2], "SI_Unit." + u[0] + "." + u[1]);
	}

	// ═══════════════════════════════════════════ naming ═══════════════════════════════════════════

	/**
	 * Same rule as AdHocAgent's <code>HasDocs.brush</code>: if the name is a keyword in any target language,
	 * capitalise its first lowercase letter (then the next one, ...) until it is not.
	 */
	static String brush(String name) {
		if (!isProhibited(name)) return name;
		String n = name;
		for (int i = 0; i < name.length(); i++)
			if (Character.isLowerCase(name.charAt(i))) {
				n = n.substring(0, i) + Character.toUpperCase(n.charAt(i)) + n.substring(i + 1);
				if (!isProhibited(n)) return n;
			}
		return name;
	}

	/** Mirrors AdHocAgent's <code>HasDocs.is_prohibited</code>: keywords of C#, C++, Java, TypeScript, Rust and Go. */
	static boolean isProhibited(String name) {
		if (name.startsWith("_") || name.endsWith("_"))
			throw new IllegalArgumentException("Entity names cannot start or end with an underscore: `" + name + "`");
		return PROHIBITED.contains(name);
	}

	static final Set<String> PROHIBITED = new java.util.HashSet<>(Arrays.asList(
			// C#
			"abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked", "class", "const", "continue",
			"decimal", "default", "delegate", "do", "double", "else", "enum", "event", "explicit", "extern", "false", "finally",
			"fixed", "float", "for", "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
			"long", "namespace", "new", "null", "object", "operator", "out", "override", "params", "private", "protected",
			"public", "readonly", "ref", "return", "sbyte", "sealed", "short", "sizeof", "stackalloc", "static", "string",
			"struct", "switch", "this", "throw", "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort",
			"using", "virtual", "void", "volatile",
			// C++
			"alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor", "char16_t", "char32_t", "compl",
			"concept", "consteval", "constexpr", "constinit", "const_cast", "decltype", "delete", "dynamic_cast", "export",
			"friend", "inline", "mutable", "noexcept", "nullptr", "or", "or_eq", "reflexpr", "register", "reinterpret_cast",
			"requires", "signed", "static_assert", "static_cast", "template", "thread_local", "typedef", "typeid", "typename",
			"union", "unsigned", "wchar_t", "while", "xor", "xor_eq",
			// Java
			"assert", "boolean", "extends", "final", "implements", "import", "instanceof", "native", "package", "strictfp",
			"super", "synchronized", "throws", "transient",
			// TypeScript
			"any", "debugger", "declare", "from", "function", "keyof", "let", "module", "never", "number", "require",
			"symbol", "type", "undefined", "unique", "unknown", "var", "with", "yield",
			// Rust
			"async", "await", "become", "box", "crate", "dyn", "fn", "impl", "loop", "macro", "match", "mod", "move", "mut",
			"priv", "pub", "self", "Self", "trait", "use", "where",
			// Go
			"chan", "defer", "fallthrough", "func", "go", "range", "select",
			// special cases across languages
			"arguments", "eval"));
}
