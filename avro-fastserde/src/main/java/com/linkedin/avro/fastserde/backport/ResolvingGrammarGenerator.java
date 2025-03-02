/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.avro.fastserde.backport;

import com.linkedin.avro.fastserde.Utils;
import com.linkedin.avroutil1.compatibility.AvroCompatibilityHelper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.avro.AvroTypeException;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.Encoder;


/**
 * The class that generates a resolving grammar to resolve between two
 * schemas.
 */
public class ResolvingGrammarGenerator extends ValidatingGrammarGenerator {
  /**
   * Resolves the writer schema writer and the reader schema
   * reader and returns the start symbol for the grammar generated.
   * @param writer    The schema used by the writer
   * @param reader    The schema used by the reader
   * @return          The start symbol for the resolving grammar
   * @throws IOException
   */
  public final Symbol generate(Schema writer, Schema reader)
    throws IOException {
    return Symbol.root(generate(writer, reader, new HashMap<>()));
  }

  /**
   * Resolves the writer schema writer and the reader schema
   * reader and returns the start symbol for the grammar generated.
   * If there is already a symbol in the map seen for resolving the
   * two schemas, then that symbol is returned. Otherwise a new symbol is
   * generated and returned.
   * @param writer    The schema used by the writer
   * @param reader    The schema used by the reader
   * @param seen      The &lt;reader-schema, writer-schema&gt; to symbol
   * map of start symbols of resolving grammars so far.
   * @return          The start symbol for the resolving grammar
   * @throws IOException
   */
  public Symbol generate(Schema writer, Schema reader,
                                Map<LitS, Symbol> seen) throws IOException
  {
    final Schema.Type writerType = writer.getType();
    final Schema.Type readerType = reader.getType();

    if (writerType == readerType) {
      switch (writerType) {
      case NULL:
        return Symbol.NULL;
      case BOOLEAN:
        return Symbol.BOOLEAN;
      case INT:
        return Symbol.INT;
      case LONG:
        return Symbol.LONG;
      case FLOAT:
        return Symbol.FLOAT;
      case DOUBLE:
        return Symbol.DOUBLE;
      case STRING:
        return Symbol.STRING;
      case BYTES:
        return Symbol.BYTES;
      case FIXED:
        if (AvroCompatibilityHelper.getSchemaFullName(writer).equals(AvroCompatibilityHelper.getSchemaFullName(reader))
            && writer.getFixedSize() == reader.getFixedSize()) {
          return Symbol.seq(Symbol.intCheckAction(writer.getFixedSize()),
              Symbol.FIXED);
        }
        break;

      case ENUM:
        if (Utils.isAbleToSupportEnumDefault()) {
          return Symbol.seq(mkEnumAdjustWithDefault(writer, reader), Symbol.ENUM);
        } else if (AvroCompatibilityHelper.getSchemaFullName(writer) == null
            || AvroCompatibilityHelper.getSchemaFullName(writer)
            .equals(AvroCompatibilityHelper.getSchemaFullName(reader))) {
          return Symbol.seq(mkEnumAdjust(writer.getEnumSymbols(), reader.getEnumSymbols()), Symbol.ENUM);
        }
        break;

      case ARRAY:
        return Symbol.seq(Symbol.repeat(Symbol.ARRAY_END,
                generate(writer.getElementType(),
                reader.getElementType(), seen)),
            Symbol.ARRAY_START);

      case MAP:
        return Symbol.seq(Symbol.repeat(Symbol.MAP_END,
                generate(writer.getValueType(),
                reader.getValueType(), seen), Symbol.STRING),
            Symbol.MAP_START);
      case RECORD:
        return resolveRecords(writer, reader, seen);
      case UNION:
        return resolveUnion(writer, reader, seen);
      default:
        throw new AvroTypeException("Unkown type for schema: " + writerType);
      }
    } else {  // writer and reader are of different types
      if (writerType == Schema.Type.UNION) {
        return resolveUnion(writer, reader, seen);
      }

      switch (readerType) {
      case LONG:
        switch (writerType) {
        case INT:
          return Symbol.resolve(super.generate(writer, seen), Symbol.LONG);
        }
        break;

      case FLOAT:
        switch (writerType) {
        case INT:
        case LONG:
          return Symbol.resolve(super.generate(writer, seen), Symbol.FLOAT);
        }
        break;

      case DOUBLE:
        switch (writerType) {
        case INT:
        case LONG:
        case FLOAT:
          return Symbol.resolve(super.generate(writer, seen), Symbol.DOUBLE);
        }
        break;

      case BYTES:
        switch (writerType) {
        case STRING:
          return Symbol.resolve(super.generate(writer, seen), Symbol.BYTES);
        }
        break;

      case STRING:
        switch (writerType) {
        case BYTES:
          return Symbol.resolve(super.generate(writer, seen), Symbol.STRING);
        }
        break;

      case UNION:
        int j = bestBranch(reader, writer, seen);
        if (j >= 0) {
          Symbol s = generate(writer, reader.getTypes().get(j), seen);
          return Symbol.seq(Symbol.unionAdjustAction(j, s), Symbol.UNION);
        }
        break;
      case NULL:
      case BOOLEAN:
      case INT:
      case ENUM:
      case ARRAY:
      case MAP:
      case RECORD:
      case FIXED:
        break;
      default:
        throw new RuntimeException("Unexpected schema type: " + readerType);
      }
    }
    return Symbol.error("Found " + AvroCompatibilityHelper.getSchemaFullName(writer)
                        + ", expecting " + AvroCompatibilityHelper.getSchemaFullName(reader));
  }

  private Symbol resolveUnion(Schema writer, Schema reader,
      Map<LitS, Symbol> seen) throws IOException {
    List<Schema> alts = writer.getTypes();
    final int size = alts.size();
    Symbol[] symbols = new Symbol[size];
    String[] labels = new String[size];

    /**
     * We construct a symbol without filling the arrays. Please see
     * {@link Symbol#production} for the reason.
     */
    int i = 0;
    for (Schema w : alts) {
      symbols[i] = generate(w, reader, seen);
      labels[i] = AvroCompatibilityHelper.getSchemaFullName(w);
      i++;
    }
    return Symbol.seq(Symbol.alt(symbols, labels),
                      Symbol.writerUnionAction());
  }

  private Symbol resolveRecords(Schema writer, Schema reader,
      Map<LitS, Symbol> seen) throws IOException {
    LitS wsc = new LitS2(writer, reader);
    Symbol result = seen.get(wsc);
    if (result == null) {
      List<Field> wfields = writer.getFields();
      List<Field> rfields = reader.getFields();

      // First, compute reordering of reader fields, plus
      // number elements in the result's production
      Field[] reordered = new Field[rfields.size()];
      int ridx = 0;
      int count = 1 + wfields.size();

      for (Field f : wfields) {
        Field rdrField = reader.getField(f.name());
        if (rdrField != null) {
          reordered[ridx++] = rdrField;
        }
      }

      for (Field rf : rfields) {
        String fname = rf.name();
        if (writer.getField(fname) == null) {
          if (!AvroCompatibilityHelper.fieldHasDefault(rf)) {
            result = Symbol.error("Found " + AvroCompatibilityHelper.getSchemaFullName(writer)
                                  + ", expecting " + AvroCompatibilityHelper.getSchemaFullName(reader)
                                  + ", missing required field " + fname);
            seen.put(wsc, result);
            return result;
          } else {
            reordered[ridx++] = rf;
            count += 3;
          }
        }
      }

      Symbol[] production = new Symbol[count];
      production[--count] = Symbol.fieldOrderAction(reordered);

      /**
       * We construct a symbol without filling the array. Please see
       * {@link Symbol#production} for the reason.
       */
      result = Symbol.seq(production);
      seen.put(wsc, result);

      /*
       * For now every field in read-record with no default value
       * must be in write-record.
       * Write record may have additional fields, which will be
       * skipped during read.
       */

      // Handle all the writer's fields
      for (Field wf : wfields) {
        String fname = wf.name();
        Field rf = reader.getField(fname);
        if (rf == null) {
          production[--count] =
            Symbol.skipAction(generate(wf.schema(), wf.schema(), seen));
        } else {
          production[--count] =
            generate(wf.schema(), rf.schema(), seen);
        }
      }

      // Add default values for fields missing from Writer
      for (Field rf : rfields) {
        String fname = rf.name();
        Field wf = writer.getField(fname);
        if (wf == null) {
          byte[] bb = getBinary(rf.schema(), AvroCompatibilityHelper.getGenericDefaultValue(rf));
          production[--count] = Symbol.defaultStartAction(bb);
          production[--count] = generate(rf.schema(), rf.schema(), seen);
          production[--count] = Symbol.DEFAULT_END_ACTION;
        }
      }
    }
    return result;
  }

  /**
   * Returns the Avro binary encoded version of n according to
   * the schema s.
   * @param s The schema for encoding
   * @param o The Object to be encoded.
   * @return  The binary encoded version of n.
   * @throws IOException
   */
  private static byte[] getBinary(Schema s, Object o) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Encoder e = AvroCompatibilityHelper.newBinaryEncoder(out);
    encode(e, s, o);
    e.flush();
    return out.toByteArray();
  }

  /**
   * Encodes the given Json node n on to the encoder e
   * according to the schema s.
   * @param e The encoder to encode into.
   * @param s The schema for the object being encoded.
   * @param o The Object to encode.
   * @throws IOException
   * @deprecated internal method
   */
  @Deprecated
  public static void encode(Encoder e, Schema s, Object o)
    throws IOException {
    switch (s.getType()) {
    case RECORD:
      GenericRecord oRecord = (GenericRecord) o;
      for (Field f : s.getFields()) {
        String name = f.name();

        Object fieldValue = oRecord.get(name);
        if (fieldValue == null) {
          if (!AvroCompatibilityHelper.fieldHasDefault(f)) {
            throw new AvroTypeException("No default value for: " + name);
          }
          fieldValue = AvroCompatibilityHelper.getGenericDefaultValue(f);
        }
        encode(e, f.schema(), fieldValue);
      }
      break;
    case ENUM:
      GenericData.EnumSymbol oEnum = (GenericData.EnumSymbol) o;
      e.writeEnum(s.getEnumOrdinal(oEnum.toString()));
      break;
    case ARRAY:
      List<Object> oArray = (List<Object>) o;
      e.writeArrayStart();
      e.setItemCount(oArray.size());
      Schema i = s.getElementType();
      for (Object element: oArray) {
        e.startItem();
        encode(e, i, element);
      }
      e.writeArrayEnd();
      break;
    case MAP:
      Map<CharSequence, Object> oMap = (Map<CharSequence, Object>) o;
      e.writeMapStart();
      e.setItemCount(oMap.size());
      Schema v = s.getValueType();
      for (Map.Entry<CharSequence, Object> mapEntry: oMap.entrySet()) {
        e.startItem();
        e.writeString(mapEntry.getKey());
        encode(e, v, mapEntry.getValue());
      }
      e.writeMapEnd();
      break;
    case UNION:
      e.writeIndex(0);
      encode(e, s.getTypes().get(0), o);
      break;
    case FIXED:
      if (!(o instanceof GenericData.Fixed)) {
        throw new AvroTypeException("Non-Fixed default value for fixed: " + o);
      }
      GenericData.Fixed oFixedLengthBytes = (GenericData.Fixed) o;
      byte[] bb = oFixedLengthBytes.bytes();
      if (bb.length != s.getFixedSize()) {
        bb = Arrays.copyOf(bb, s.getFixedSize());
      }
      e.writeFixed(bb);
      break;
    case STRING:
      if (!(o instanceof CharSequence)) {
        throw new AvroTypeException("Non-string default value for string: " + o);
      }
      CharSequence oString = (CharSequence) o;
      e.writeString(oString);
      break;
    case BYTES:
      if (!(o instanceof ByteBuffer)) {
        throw new AvroTypeException("Non-ByteBuffer default value for bytes: " + o);
      }
      ByteBuffer oByteBuffer = (ByteBuffer) o;
      e.writeBytes(oByteBuffer);
      break;
    case INT:
      if (!(o instanceof Integer)) {
        throw new AvroTypeException("Non-numeric default value for int: " + o);
      }
      Integer oInt = (Integer) o;
      e.writeInt(oInt);
      break;
    case LONG:
      if (!(o instanceof Long)) {
        throw new AvroTypeException("Non-numeric default value for long: " + o);
      }
      Long oLong = (Long) o;
      e.writeLong(oLong);
      break;
    case FLOAT:
      if (!(o instanceof Float)) {
        throw new AvroTypeException("Non-numeric default value for float: " + o);
      }
      Float oFloat = (Float) o;
      e.writeFloat(oFloat);
      break;
    case DOUBLE:
      if (!(o instanceof Double)) {
        throw new AvroTypeException("Non-numeric default value for double: " + o);
      }
      Double oDouble = (Double) o;
      e.writeDouble(oDouble);
      break;
    case BOOLEAN:
      if (!(o instanceof Boolean)) {
        throw new AvroTypeException("Non-boolean default for boolean: " + o);
      }
      Boolean oBoolean = (Boolean) o;
      e.writeBoolean(oBoolean);
      break;
    case NULL:
      if (null != o) {
        throw new AvroTypeException("Non-null default value for null type: " + o);
      }
      e.writeNull();
      break;
    }
  }

  private static Symbol mkEnumAdjust(List<String> wsymbols,
      List<String> rsymbols){
    Object[] adjustments = new Object[wsymbols.size()];
    for (int i = 0; i < adjustments.length; i++) {
      int j = rsymbols.indexOf(wsymbols.get(i));
      adjustments[i] = (j == -1 ? "No match for " + wsymbols.get(i)
                                : new Integer(j));
    }
    return Symbol.enumAdjustAction(rsymbols.size(), adjustments);
  }

  private static Symbol mkEnumAdjustWithDefault(Schema writer, Schema reader) {
    Avro19Resolver.EnumAdjust e = (Avro19Resolver.EnumAdjust) Avro19Resolver.EnumAdjust.resolve(writer, reader, GenericData.get());
    Object[] adjs = new Object[e.adjustments.length];
    for (int i = 0; i < adjs.length; i++) {
      adjs[i] = (0 <= e.adjustments[i] ? new Integer(e.adjustments[i])
          : "No match for " + e.writer.getEnumSymbols().get(i));
    }
    return Symbol.enumAdjustAction(e.reader.getEnumSymbols().size(), adjs);
  }

  /**
   * This checks if the symbol itself is an error or if there is an error in
   * its production.
   *
   * When the symbol is created for a record, this checks whether the record
   * fields are present (the symbol is not an error action) and that all of the
   * fields have a non-error action. Record fields may have nested error
   * actions.
   *
   * @return true if the symbol is an error or if its production has an error
   */
  private boolean hasMatchError(Symbol sym) {
    if (sym instanceof Symbol.ErrorAction) {
      return true;
    } else {
      for (int i = 0; i < sym.production.length; i += 1) {
        if (sym.production[i] instanceof Symbol.ErrorAction) {
          return true;
        }
      }
    }
    return false;
  }

  private int bestBranch(Schema r, Schema w, Map<LitS, Symbol> seen) throws IOException {
    Schema.Type vt = w.getType();
      // first scan for exact match
      int j = 0;
      int structureMatch = -1;
      for (Schema b : r.getTypes()) {
        if (vt == b.getType())
          if (vt == Schema.Type.RECORD || vt == Schema.Type.ENUM ||
              vt == Schema.Type.FIXED) {
            String vname = AvroCompatibilityHelper.getSchemaFullName(w);
            String bname = AvroCompatibilityHelper.getSchemaFullName(b);
            // return immediately if the name matches exactly according to spec
            if (vname != null && vname.equals(bname))
              return j;

            if (vt == Schema.Type.RECORD &&
                !hasMatchError(resolveRecords(w, b, seen))) {
              String vShortName = w.getName();
              String bShortName = b.getName();
              // use the first structure match or one where the name matches
              if ((structureMatch < 0) ||
                  (vShortName != null && vShortName.equals(bShortName))) {
                structureMatch = j;
              }
            }
          } else
            return j;
        j++;
      }

      // if there is a record structure match, return it
      if (structureMatch >= 0)
        return structureMatch;

      // then scan match via numeric promotion
      j = 0;
      for (Schema b : r.getTypes()) {
        switch (vt) {
        case INT:
          switch (b.getType()) {
          case LONG: case DOUBLE:
            return j;
          }
          break;
        case LONG:
        case FLOAT:
          switch (b.getType()) {
          case DOUBLE:
            return j;
          }
          break;
        case STRING:
          switch (b.getType()) {
          case BYTES:
            return j;
          }
          break;
        case BYTES:
          switch (b.getType()) {
          case STRING:
            return j;
          }
          break;
        }
        j++;
      }
      return -1;
  }

  /**
   * Clever trick which differentiates items put into
   * <code>seen</code> by {@link ValidatingGrammarGenerator#validating()}
   * from those put in by {@link ValidatingGrammarGenerator#resolving()}.
   */
   static class LitS2 extends ValidatingGrammarGenerator.LitS {
     public Schema expected;
     public LitS2(Schema actual, Schema expected) {
       super(actual);
       this.expected = expected;
     }
     public boolean equals(Object o) {
       if (! (o instanceof LitS2)) return false;
       LitS2 other = (LitS2) o;
       return actual == other.actual && expected == other.expected;
     }
     public int hashCode() {
       return super.hashCode() + expected.hashCode();
     }
   }
}

