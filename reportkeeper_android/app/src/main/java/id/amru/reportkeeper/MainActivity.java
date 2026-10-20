package id.amru.reportkeeper;
import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.regex.*;

public class MainActivity extends Activity {
  static final int PICK_SOURCE=51, PICK_REVIEW=52;
  final ExecutorService jobs=Executors.newSingleThreadExecutor();
  final HashSet<String> selected=new HashSet<>();
  final ArrayList<FileEntry> files=new ArrayList<>();
  final HashSet<String> duplicate=new HashSet<>(), older=new HashSet<>();
  LinearLayout screen; TextView status; boolean working=false;
  final int blue=Color.rgb(20,71,199), ink=Color.rgb(20,34,59);
  String message="Choose a source folder to scan. No files are changed during scanning.";
  static final class FileEntry {
    String tree,id,parent,name,folder,mime,key; long size,modified;
    Uri documentUri() { return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree),id); }
  }
  static final class FileCopy {
    String tree,doc,sourceTree,sourceParent,originalName,reviewName; long size,created;
    JSONObject json() throws JSONException { JSONObject j=new JSONObject();
      j.put("tree",tree);j.put("doc",doc);j.put("sourceTree",sourceTree);j.put("sourceParent",sourceParent);
      j.put("originalName",originalName);j.put("reviewName",reviewName);j.put("size",size);j.put("created",created); return j; }
    static FileCopy parse(JSONObject j) throws JSONException {FileCopy f=new FileCopy();
      f.tree=j.getString("tree");f.doc=j.getString("doc");f.sourceTree=j.getString("sourceTree");
      f.sourceParent=j.getString("sourceParent");f.originalName=j.getString("originalName");
      f.reviewName=j.getString("reviewName");f.size=j.getLong("size");f.created=j.getLong("created");return f;}
  }
  android.content.SharedPreferences prefs(){return getSharedPreferences("rk",MODE_PRIVATE);}
  JSONArray array(String key){try{return new JSONArray(prefs().getString(key,"[]"));}catch(Exception e){return new JSONArray();}}
  ArrayList<String> roots(){ArrayList<String> r=new ArrayList<>();JSONArray a=array("roots");for(int i=0;i<a.length();i++)r.add(a.optString(i));return r;}
  String review(){return prefs().getString("review",null);}
  void update(String text){runOnUiThread(()->{message=text;render();});}
  @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(Color.rgb(10,27,59));render();}
  @Override public void onDestroy(){jobs.shutdownNow();super.onDestroy();}
  int px(int d){return (int)(d*getResources().getDisplayMetrics().density+0.5f);}
  TextView label(String s,int sz,boolean strong){TextView t=new TextView(this);t.setText(s);t.setTextSize(sz);t.setTextColor(ink);if(strong)t.setTypeface(null,1);t.setPadding(px(2),px(5),px(2),px(5));return t;}
  Button btn(String text,Runnable click){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setOnClickListener(v->click.run());b.setEnabled(!working);return b;}
  void gap(LinearLayout c,int h){View v=new View(this);c.addView(v,new LinearLayout.LayoutParams(1,px(h)));}
  void render(){
    ScrollView scroll=new ScrollView(this);LinearLayout content=new LinearLayout(this);content.setOrientation(1);content.setPadding(px(16),px(14),px(16),px(20));content.setBackgroundColor(Color.rgb(246,248,252));scroll.addView(content);screen=content;
    TextView title=label("REPORTKEEPER",23,true);title.setTextColor(blue);content.addView(title);
    content.addView(label("Android storage manager | Native • offline",12,false));
    gap(content,10);content.addView(label("Source folders",17,true));
    ArrayList<String> rs=roots();if(rs.isEmpty())content.addView(label("No folders selected.",13,false));
    for(String r:rs){String shortName=Uri.parse(r).getLastPathSegment(); content.addView(label("• "+shortName,12,false));}
    content.addView(btn("Add source folder",()->picker(PICK_SOURCE)));
    content.addView(label("Review folder: "+(review()==null?"Not selected":Uri.parse(review()).getLastPathSegment()),12,false));
    content.addView(btn("Choose manual-review destination",()->picker(PICK_REVIEW)));
    gap(content,8);content.addView(label("Find duplicates and old revisions",17,true));
    content.addView(btn("SCAN NOW",()->scan()));
    content.addView(label(message,12,false));gap(content,10);
    if(!files.isEmpty()){
      long bytes=0;for(FileEntry f:files)if(duplicate.contains(f.key)||older.contains(f.key))bytes+=f.size;
      content.addView(label(files.size()+" reports scanned  |  "+duplicate.size()+" exact duplicates  |  "+older.size()+" old-version candidates",14,true));
      content.addView(label("Potentially reviewable: "+fmt(bytes)+". Keep latest 3 revision groups; versions are inferred from filenames. Confirm all actions manually.",12,false));
      content.addView(btn("Select all recommended candidates",()->{for(FileEntry f:files)if(duplicate.contains(f.key)||older.contains(f.key))selected.add(f.key);render();}));
      content.addView(btn("Clear selection",()->{selected.clear();render();}));
      gap(content,6);
      ArrayList<FileEntry> shown=new ArrayList<>();
      for(FileEntry f:files)if(duplicate.contains(f.key)||older.contains(f.key))shown.add(f);
      shown.sort((a,b)->Long.compare(b.modified,a.modified));
      for(FileEntry f:shown){
        String kind=duplicate.contains(f.key)?"EXACT DUPLICATE":"OLD VERSION • REVIEW";
        CheckBox check=new CheckBox(this);check.setText(kind+"\n"+f.name+"\n"+f.folder+"  •  "+fmt(f.size));
        check.setTextColor(ink);check.setTextSize(12);check.setPadding(px(4),px(5),px(4),px(5));
        check.setChecked(selected.contains(f.key));check.setOnCheckedChangeListener((button,isChecked)->{if(isChecked)selected.add(f.key);else selected.remove(f.key);});
        content.addView(check);View line=new View(this);line.setBackgroundColor(Color.rgb(223,230,241));content.addView(line,new LinearLayout.LayoutParams(-1,px(1)));
      }
      content.addView(label(selected.size()+" selected",14,true));
      content.addView(btn("SET ASIDE FOR MANUAL REVIEW",()->confirm(false)));
      content.addView(btn("DELETE SELECTED FILES",()->confirm(true)));
    }
    gap(content,12);content.addView(label("Review history and restore",17,true));
    for(FileCopy c:history()){
      content.addView(label(c.originalName+" • "+fmt(c.size),12,false));
      content.addView(btn("Restore: "+c.originalName,()->restoreConfirm(c)));
    }
    gap(content,10);content.addView(label("Safety: the app only accesses Android folders you explicitly choose. Deletion may be permanent. Review copies on the same storage do NOT free disk space.",11,false));
    if(working){status=label("Working…",11,false);content.addView(status);}
    setContentView(scroll);
  }
  void picker(int code){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,code);}
  @Override protected void onActivityResult(int rc,int result,Intent data){super.onActivityResult(rc,result,data);
    if(result!=RESULT_OK||data==null||data.getData()==null)return;
    Uri uri=data.getData();try{
      int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      getContentResolver().takePersistableUriPermission(uri,flags);
      if(rc==PICK_SOURCE){ArrayList<String> a=roots();if(!a.contains(uri.toString()))a.add(uri.toString());prefs().edit().putString("roots",new JSONArray(a).toString()).apply();}
      if(rc==PICK_REVIEW)prefs().edit().putString("review",uri.toString()).apply();
      files.clear();duplicate.clear();older.clear();selected.clear();message="Folder selected. Start a new scan.";
    }catch(Exception e){message="Could not access selected folder: "+e.getMessage();}
    render();
  }
  static boolean isReport(String s){return s.toLowerCase(Locale.ROOT).matches(".*\\.(pdf|docx?|xlsx?|pptx?|odt|ods|odp|txt|csv|rtf)");}
  Uri doc(String tree,String id){return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree),id);}
  interface ChildVisitor{void found(String id,String name,String mime,long size,long changed) throws Exception;}
  void children(String tree,String parent,ChildVisitor visitor) throws Exception{
    Uri u=DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(tree),parent);
    String[] p={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    try(Cursor c=getContentResolver().query(u,p,null,null,null)){
      if(c==null)throw new IOException("Folder unavailable");
      while(c.moveToNext())visitor.found(c.getString(0),c.getString(1),c.getString(2),c.isNull(3)?0:c.getLong(3),c.isNull(4)?0:c.getLong(4));
    }
  }
  void scan(){if(working||roots().isEmpty()){message="Select a source folder first.";render();return;}
    working=true;files.clear();duplicate.clear();older.clear();selected.clear();update("Scanning reports and SHA-256 identical files…");
    jobs.submit(()->{
      final ArrayList<String> issues=new ArrayList<>();
      try {
        for(String root:roots()){
          String first=DocumentsContract.getTreeDocumentId(Uri.parse(root));ArrayDeque<String[]> q=new ArrayDeque<>();
          q.add(new String[]{first,""});HashSet<String> seen=new HashSet<>();
          while(!q.isEmpty()){
            String[] cur=q.removeFirst();if(!seen.add(cur[0]))continue;
            try{children(root,cur[0],(id,name,mime,size,changed)->{
              if(name==null||id==null||"ReportKeeper_Review".equalsIgnoreCase(name))return;
              if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){q.add(new String[]{id,cur[1]+"/"+name});return;}
              if(!isReport(name))return;
              FileEntry f=new FileEntry();f.tree=root;f.id=id;f.parent=cur[0];f.name=name;f.folder=cur[1];f.mime=mime;f.key=root+"|"+id;f.size=size;f.modified=changed;files.add(f);
            });}catch(Exception e){issues.add("Scan: "+e.getMessage());}
          }
        }
        Map<Long,List<FileEntry>> bySize=new HashMap<>();
        for(FileEntry f:files)if(f.size>0)bySize.computeIfAbsent(f.size,k->new ArrayList<>()).add(f);
        Map<String,List<FileEntry>> byHash=new HashMap<>();
        for(List<FileEntry> sameSize:bySize.values())if(sameSize.size()>1)for(FileEntry f:sameSize){
          try{String hash=f.size+":"+hash(f.documentUri());byHash.computeIfAbsent(hash,k->new ArrayList<>()).add(f);}
          catch(Exception e){issues.add("Hash "+f.name+": "+e.getMessage());}
        }
        for(List<FileEntry> matched:byHash.values())if(matched.size()>1){
          matched.sort((a,b)->Long.compare(b.modified,a.modified));for(int i=1;i<matched.size();i++)duplicate.add(matched.get(i).key);
        }
        Map<String,Map<String,List<FileEntry>>> families=new HashMap<>();
        for(FileEntry f:files){String family=f.tree+"|"+f.folder+"|"+family(f.name);
          families.computeIfAbsent(family,k->new HashMap<>()).computeIfAbsent(version(f.name),k->new ArrayList<>()).add(f);
        }
        for(Map<String,List<FileEntry>> revisions:families.values())if(revisions.size()>3){
          ArrayList<String> ids=new ArrayList<>(revisions.keySet());
          ids.sort((a,b)->Long.compare(rank(b,revisions.get(b)),rank(a,revisions.get(a))));
          for(int i=3;i<ids.size();i++)for(FileEntry f:revisions.get(ids.get(i)))older.add(f.key);
        }
        String msg="Scan complete: "+files.size()+" reports, "+duplicate.size()+" identical extra copies, "+older.size()+" old revisions.";
        if(!issues.isEmpty())msg+=" "+issues.size()+" folders/files had read errors.";
        update(msg);
      }catch(Exception e){update("Scan failed: "+e.getMessage());}
      finally{runOnUiThread(()->{working=false;render();});}
    });
  }
  static String stem(String n){int i=n.lastIndexOf('.');return i<0?n:n.substring(0,i);}
  static String family(String name){
    String s=stem(name).toLowerCase(Locale.ROOT).replaceAll("[_().-]+"," ");
    s=s.replaceAll("\\b(?:rev(?:ision)?|ver(?:sion)?|v)\\s*\\d{1,4}\\b"," ");
    s=s.replaceAll("\\b20\\d{2}[ /-]?(?:0[1-9]|1[0-2])[ /-]?(?:[0-2]\\d|3[01])\\b"," ");
    s=s.replaceAll("\\b(?:final|draft|latest|review|clean|signed|revised|update|updated)\\b"," ");
    return s.replaceAll("\\s+"," ").trim();
  }
  static String version(String name){String n=stem(name).toLowerCase(Locale.ROOT).replace('_',' ');
    Matcher m=Pattern.compile("\\b(?:rev(?:ision)?|ver(?:sion)?|v)[ .-]*(\\d{1,4})\\b").matcher(n);
    if(m.find())return "r:"+Integer.parseInt(m.group(1));
    m=Pattern.compile("(20\\d{2})[-. ]?(0[1-9]|1[0-2])[-. ]?([0-2]\\d|3[01])").matcher(n);
    if(m.find())return "d:"+m.group(1)+m.group(2)+m.group(3);
    if(n.contains("final"))return "final";if(n.contains("draft"))return "draft";return "base";
  }
  static long rank(String id,List<FileEntry> files){long latest=0;for(FileEntry f:files)latest=Math.max(latest,f.modified);
    try{if(id.startsWith("r:"))return 4000000000000L+Long.parseLong(id.substring(2))*10000000L+latest/1000000000L;
      if(id.startsWith("d:"))return 3000000000000L+Long.parseLong(id.substring(2))*10000L;
    }catch(Exception ignored){}
    if(id.equals("final"))return 2000000000000L+latest/1000L;
    if(id.equals("draft"))return 500000000000L+latest/1000L;
    return 1000000000000L+latest/1000L;
  }
  String hash(Uri uri)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");
    try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new FileNotFoundException("Cannot read file");
      byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
    StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.US,"%02x",b&255));return s.toString();
  }
  String fmt(long sz){if(sz<1024)return sz+" B";double d=sz;String[] units={"B","KB","MB","GB","TB"};int i=0;
    while(d>=1024&&i<4){d/=1024;i++;}return String.format(Locale.US,"%.1f %s",d,units[i]);}
  void confirm(boolean delete){ArrayList<FileEntry> chosen=new ArrayList<>();for(FileEntry f:files)if(selected.contains(f.key))chosen.add(f);
    if(chosen.isEmpty()){message="Select one or more files first.";render();return;}
    if(!delete&&review()==null){message="Choose a review destination first.";render();return;}
    long total=0;for(FileEntry f:chosen)total+=f.size;String info=chosen.size()+" files • "+fmt(total)+".\n";
    if(delete)info+="WARNING: Android providers may delete permanently; there may be no Recycle Bin. Not reversible.";
    else info+="Copy to review folder, verify SHA-256, THEN delete original. You can restore from review history. Same-storage moves do not save space.";
    new AlertDialog.Builder(this).setTitle(delete?"Delete selected files?":"Set aside selected files?")
      .setMessage(info).setNegativeButton("Cancel",null).setPositiveButton(delete?"DELETE":"SET ASIDE",(a,b)->operate(chosen,delete)).show();
  }
  void operate(List<FileEntry> list,boolean deletion){working=true;update("Working on selected files…");
    jobs.submit(()->{int done=0;ArrayList<String> errs=new ArrayList<>();
      for(FileEntry f:list){try{
        if(deletion){if(!DocumentsContract.deleteDocument(getContentResolver(),f.documentUri()))throw new IOException("Provider refused delete");}
        else quarantine(f);
        done++;
      }catch(Exception e){errs.add(f.name+": "+e.getMessage());}}
      final int ok=done;runOnUiThread(()->{
        working=false;files.clear();selected.clear();duplicate.clear();older.clear();
        message=ok+" file(s) processed. Rescan to update results.";
        render();
        new AlertDialog.Builder(this).setTitle("Operation completed").setMessage(message+(errs.isEmpty()?"":"\n"+String.join("\n",errs.subList(0,Math.min(4,errs.size())))))
        .setPositiveButton("OK",null).show();
      });
    });
  }
  void copyVerified(Uri from,Uri to)throws Exception{
    String expected=hash(from);try(InputStream in=getContentResolver().openInputStream(from);
       OutputStream out=getContentResolver().openOutputStream(to,"w")){
      if(in==null||out==null)throw new IOException("Cannot open source/destination");
      byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
      out.flush();
    }
    if(!expected.equals(hash(to)))throw new IOException("SHA-256 check failed");
  }
  String ensureReview(String tree)throws Exception{
    String root=DocumentsContract.getTreeDocumentId(Uri.parse(tree));final String[] result={null};
    children(tree,root,(id,name,mime,size,time)->{if("ReportKeeper_Review".equals(name)&&DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))result[0]=id;});
    if(result[0]!=null)return result[0];
    Uri folder=DocumentsContract.createDocument(getContentResolver(),doc(tree,root),DocumentsContract.Document.MIME_TYPE_DIR,"ReportKeeper_Review");
    if(folder==null)throw new IOException("Cannot create review folder");
    return DocumentsContract.getDocumentId(folder);
  }
  void quarantine(FileEntry f)throws Exception{
    String dstTree=review();String folder=ensureReview(dstTree);
    String name=System.currentTimeMillis()+"_"+f.name.replaceAll("[/\\\\]","_");
    Uri created=DocumentsContract.createDocument(getContentResolver(),doc(dstTree,folder),f.mime,name);
    if(created==null)throw new IOException("Could not create review copy");
    try{copyVerified(f.documentUri(),created);}
    catch(Exception ex){try{DocumentsContract.deleteDocument(getContentResolver(),created);}catch(Exception ignored){}throw ex;}
    // Preserve verified copy when provider refuses deletion; original remains available.
    if(!DocumentsContract.deleteDocument(getContentResolver(),f.documentUri()))
      throw new IOException("Original was not removed; verified review copy was retained");
    FileCopy c=new FileCopy();c.tree=dstTree;c.doc=DocumentsContract.getDocumentId(created);
    c.sourceTree=f.tree;c.sourceParent=f.parent;c.originalName=f.name;c.reviewName=name;c.size=f.size;c.created=System.currentTimeMillis();
    ArrayList<FileCopy> records=history();records.add(0,c);saveHistory(records);
  }
  ArrayList<FileCopy> history(){ArrayList<FileCopy> a=new ArrayList<>();JSONArray json=array("history");
    for(int i=0;i<json.length();i++)try{a.add(FileCopy.parse(json.getJSONObject(i)));}catch(Exception ignored){}return a;
  }
  void saveHistory(List<FileCopy> entries){JSONArray a=new JSONArray();for(FileCopy e:entries)try{a.put(e.json());}catch(Exception ignored){}
    prefs().edit().putString("history",a.toString()).commit();
  }
  void restoreConfirm(FileCopy e){new AlertDialog.Builder(this).setTitle("Restore file?")
    .setMessage(e.originalName+"\nRestores to original folder. Conflicting names are renamed, never overwritten.")
    .setNegativeButton("Cancel",null).setPositiveButton("RESTORE",(d,w)->{
      working=true;update("Restoring file…");
      jobs.submit(()->{String error=null;try{
        final boolean[] exists={false};children(e.sourceTree,e.sourceParent,(id,n,m,s,t)->{if(n.equals(e.originalName))exists[0]=true;});
        String outName=exists[0]?"RESTORED_"+System.currentTimeMillis()+"_"+e.originalName:e.originalName;
        Uri original=doc(e.tree,e.doc);
        Uri created=DocumentsContract.createDocument(getContentResolver(),doc(e.sourceTree,e.sourceParent),mime(e.originalName),outName);
        if(created==null)throw new IOException("Unable to create restored file");
        try{copyVerified(original,created);}catch(Exception x){try{DocumentsContract.deleteDocument(getContentResolver(),created);}catch(Exception ignored){}throw x;}
        if(!DocumentsContract.deleteDocument(getContentResolver(),original))
          throw new IOException("Verified restored copy is safe but review source could not be deleted.");
        ArrayList<FileCopy> entries=history();entries.removeIf(c->c.doc.equals(e.doc)&&c.tree.equals(e.tree));saveHistory(entries);
      }catch(Exception ex){error=ex.getMessage();}
      final String outcome=error;runOnUiThread(()->{working=false;message=outcome==null?"Restore successful.":"Restore issue: "+outcome;render();});
      });
    }).show();}
  static String mime(String n){String s=n.toLowerCase(Locale.ROOT);
    if(s.endsWith(".pdf"))return "application/pdf";
    if(s.endsWith(".docx"))return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    if(s.endsWith(".xlsx"))return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    if(s.endsWith(".pptx"))return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    if(s.endsWith(".csv"))return "text/csv";return "application/octet-stream";
  }
}
