package review.mobile;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Read-only forensic entry. Prints parser metadata only, never answer text or exception messages. */
public final class GeminiSnapshotAudit {
    public static Map<String,Object> audit(Path path)throws Exception{
        byte[] bytes=Files.readAllBytes(path);var result=new LinkedHashMap<String,Object>();result.put("bytes",bytes.length);
        var mapper=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
        try(var parser=mapper.getFactory().createParser(bytes)){
            JsonNode tree=mapper.readTree(parser);if(parser.nextToken()!=null)throw new IllegalArgumentException("Trailing tokens");
            result.put("valid",true);result.put("root",tree.getNodeType().name());result.put("rows",tree.isArray()?tree.size():-1);
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){
            result.put("valid",false);result.put("errorType",invalid.getClass().getSimpleName());result.put("line",invalid.getLocation().getLineNr());result.put("column",invalid.getLocation().getColumnNr());result.put("byteOffset",invalid.getLocation().getByteOffset());
        }
        boolean duplicate=false,caseCollision=false;String lastField="";int fields=0;
        var scopes=new ArrayDeque<Map<String,String>>();var exact=new ArrayDeque<Set<String>>();
        try(var parser=new com.fasterxml.jackson.core.JsonFactory().createParser(bytes)){
            com.fasterxml.jackson.core.JsonToken token;
            while((token=parser.nextToken())!=null){
                if(token==com.fasterxml.jackson.core.JsonToken.START_OBJECT){scopes.push(new HashMap<>());exact.push(new HashSet<>());}
                else if(token==com.fasterxml.jackson.core.JsonToken.END_OBJECT){scopes.pop();exact.pop();}
                else if(token==com.fasterxml.jackson.core.JsonToken.FIELD_NAME){
                    lastField=parser.currentName();fields++;if(!exact.peek().add(lastField))duplicate=true;
                    String previous=scopes.peek().putIfAbsent(lastField.toLowerCase(Locale.ROOT),lastField);if(previous!=null&&!previous.equals(lastField))caseCollision=true;
                }
            }
        }catch(com.fasterxml.jackson.core.JsonProcessingException incomplete){/* Only parser metadata is reported. */}
        result.put("duplicateKeysBeforeFailure",duplicate);result.put("caseCollisionsBeforeFailure",caseCollision);result.put("lastFieldIsCreatedAt",lastField.equals("createdAt"));result.put("fieldCount",fields);
        return result;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Read-only JSON path required");
        System.out.println(new ObjectMapper().writeValueAsString(audit(Path.of(args[0]))));
    }
}
