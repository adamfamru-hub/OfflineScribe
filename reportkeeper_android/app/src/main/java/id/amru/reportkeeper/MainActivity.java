package id.amru.reportkeeper;
import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
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
  static final int PICK_SOURCE=51, PICK_REVIEW=52, PICK_BUCKET=53;
  String pendingBucketName="";
  String tab="HOME",query="",filter="All",sort="Newest";
  int page=0; static final int PAGE_SIZE=80;
  LinearLayout root,top,nav,toolbar,footer; ListView listView; TextView info,pager,selectionLabel;
  final ArrayList<RowItem> allRows=new ArrayList<>(),displayRows=new ArrayList<>();
  final Map<String,ArrayList<FileEntry>> dupGroups=new LinkedHashMap<>(),revGroups=new LinkedHashMap<>();
  final String[] categories={"Documents","Photos","Videos","Audio","Archives","Other"};
  boolean refreshing=false;
  static final class RowItem { String title,sub,kind,key; FileEntry f; FileCopy h; boolean group; RowItem(String t,String u,boolean g){title=t;sub=u;group=g;} }
  static final class Bucket {String name,tree;Bucket(String n,String t){name=n;tree=t;}}
  ArrayList<Bucket> buckets(){ArrayList<Bucket> out=new ArrayList<>();JSONArray j=array("buckets");for(int i=0;i<j.length();i++)try{JSONObject o=j.getJSONObject(i);out.add(new Bucket(o.getString("name"),o.getString("tree")));}catch(Exception ignored){}return out;}
  void saveBuckets(List<Bucket> b){JSONArray a=new JSONArray();for(Bucket k:b){JSONObject o=new JSONObject();try{o.put("name",k.name);o.put("tree",k.tree);a.put(o);}catch(Exception ignored){}}prefs().edit().putString("buckets",a.toString()).apply();}
  boolean allowed(String kind){return prefs().getBoolean("type_"+kind,true);}

  final ExecutorService jobs=Executors.newSingleThreadExecutor();
  final HashSet<String> selected=new HashSet<>();
  final ArrayList<FileEntry> files=new ArrayList<>();
  final HashSet<String> duplicate=new HashSet<>(), older=new HashSet<>();
  LinearLayout screen; TextView status; boolean working=false;
  final int blue=Color.rgb(20,71,199), ink=Color.rgb(20,34,59);
  String message="Choose a source folder to scan. No files are changed during scanning.";
  static final class FileEntry {
    String tree,id,parent,name,folder,mime,key,category,hashKey; long size,modified;
    Uri documentUri() { return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree),id); }
  }
  static final class FileCopy {
    String tree,doc,sourceTree,sourceParent,originalName,reviewName,bucket; long size,created;
    JSONObject json() throws JSONException { JSONObject j=new JSONObject();
      j.put("tree",tree);j.put("doc",doc);j.put("sourceTree",sourceTree);j.put("sourceParent",sourceParent);
      j.put("originalName",originalName);j.put("reviewName",reviewName);j.put("bucket",bucket==null?"Review":bucket);j.put("size",size);j.put("created",created); return j; }
    static FileCopy parse(JSONObject j) throws JSONException {FileCopy f=new FileCopy();
      f.tree=j.getString("tree");f.doc=j.getString("doc");f.sourceTree=j.getString("sourceTree");
      f.sourceParent=j.getString("sourceParent");f.originalName=j.getString("originalName");
      f.reviewName=j.getString("reviewName");f.bucket=j.optString("bucket","Review");f.size=j.getLong("size");f.created=j.getLong("created");return f;}
  }
  android.content.SharedPreferences prefs(){return getSharedPreferences("rk",MODE_PRIVATE);}
  JSONArray array(String key){try{return new JSONArray(prefs().getString(key,"[]"));}catch(Exception e){return new JSONArray();}}
  ArrayList<String> roots(){ArrayList<String> r=new ArrayList<>();JSONArray a=array("roots");for(int i=0;i<a.length();i++)r.add(a.optString(i));return r;}
  String review(){return prefs().getString("review",null);}
  void update(String text){runOnUiThread(()->{message=text;if(info!=null)info.setText(text);});}
  @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(Color.rgb(10,27,59));getWindow().setNavigationBarColor(Color.WHITE);render();if(!prefs().getBoolean("intro_seen",false)){prefs().edit().putBoolean("intro_seen",true).apply();new AlertDialog.Builder(this).setTitle("Welcome to ReportKeeper").setMessage("Created by PERSPICAL\nDeveloped by Adam F. Amru\n\nOrganize your device. Detect byte-identical copies with SHA-256, review older revisions, and route files into folders you choose.\n\nAll scanning happens on your device. File changes always require your confirmation.").setPositiveButton("GET STARTED",null).show();}}
  @Override public void onDestroy(){jobs.shutdownNow();super.onDestroy();}
  int px(int d){return (int)(d*getResources().getDisplayMetrics().density+0.5f);}
  TextView label(String s,int sz,boolean strong){TextView t=new TextView(this);t.setText(s);t.setTextSize(sz);t.setTextColor(ink);if(strong)t.setTypeface(null,1);t.setPadding(px(2),px(5),px(2),px(5));return t;}
  Button btn(String text,Runnable click){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setOnClickListener(v->click.run());b.setEnabled(!working);b.setMinHeight(px(42));b.setTextSize(12);return b;}
  void gap(LinearLayout c,int h){View v=new View(this);c.addView(v,new LinearLayout.LayoutParams(1,px(h)));}
  void render(){
    // Only the list region scrolls. Tabs, search/filter controls, and actions remain visible.
    root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Color.rgb(247,249,253));
    top=new LinearLayout(this);top.setOrientation(1);top.setPadding(px(15),px(12),px(15),px(8));top.setBackgroundColor(Color.rgb(10,29,67));
    TextView logo=label("◈  FILE MANAGER  ·  REPORTKEEPER",18,true);logo.setTextColor(Color.WHITE);top.addView(logo);
    TextView credit=label("Created by PERSPICAL  ·  Developed by Adam F. Amru",11,false);credit.setTextColor(Color.rgb(188,212,251));top.addView(credit);
    info=label(message,12,false);info.setTextColor(Color.rgb(202,229,255));info.setMaxLines(2);top.addView(info);
    root.addView(top);
    HorizontalScrollView navScroll=new HorizontalScrollView(this);navScroll.setHorizontalScrollBarEnabled(false);
    nav=new LinearLayout(this);nav.setOrientation(0);nav.setBackgroundColor(Color.WHITE);
    String[] titles={"HOME","COPIES","VERSIONS","FILES","REVIEW"};
    for(String t:titles){TextView n=label(t,12,t.equals(tab));n.setPadding(px(12),px(12),px(12),px(12));n.setTextColor(t.equals(tab)?blue:ink);n.setOnClickListener(v->{tab=t;page=0;query="";filter="All";render();});nav.addView(n);}
    navScroll.addView(nav);root.addView(navScroll);
    toolbar=new LinearLayout(this);toolbar.setOrientation(1);toolbar.setPadding(px(10),px(5),px(10),px(5));root.addView(toolbar);
    if(tab.equals("HOME"))renderHomeToolbar();
    else if(tab.equals("REVIEW"))renderReviewToolbar();
    else renderBrowseToolbar();
    listView=new ListView(this);listView.setDividerHeight(px(1));listView.setCacheColorHint(Color.TRANSPARENT);
    listView.setChoiceMode(ListView.CHOICE_MODE_NONE);
    root.addView(listView,new LinearLayout.LayoutParams(-1,0,1));
    footer=new LinearLayout(this);footer.setOrientation(1);footer.setBackgroundColor(Color.WHITE);footer.setPadding(px(10),px(4),px(10),px(7));root.addView(footer);
    if(tab.equals("HOME")){TextView t=label("Offline processing  ·  Folder permissions only  ·  No automatic deletion",11,false);footer.addView(t);}
    else if(tab.equals("REVIEW")){TextView t=label("Restore returns files to their original folder when accessible.",11,false);footer.addView(t);}
    else {
      selectionLabel=label(selected.size()+" selected",12,true);footer.addView(selectionLabel);
      LinearLayout actions=new LinearLayout(this);actions.setOrientation(0);
      Button pick=btn("SELECT CANDIDATES",()->{for(RowItem r:allRows)if(r.f!=null&&(duplicate.contains(r.f.key)||older.contains(r.f.key)))selected.add(r.f.key);refreshList();});
      actions.addView(pick,new LinearLayout.LayoutParams(0,px(48),1));
      Button clear=btn("CLEAR",()->{selected.clear();refreshList();});actions.addView(clear,new LinearLayout.LayoutParams(0,px(48),0.55f));
      footer.addView(actions);
      LinearLayout ops=new LinearLayout(this);ops.setOrientation(0);
      ops.addView(btn("MOVE / REVIEW",()->confirm(false)),new LinearLayout.LayoutParams(0,px(46),1));
      ops.addView(btn("DELETE",()->confirm(true)),new LinearLayout.LayoutParams(0,px(46),0.6f));
      footer.addView(ops);
    }
    setContentView(root);refreshList();
  }
  void renderHomeToolbar(){
    toolbar.addView(label("SOURCE FOLDERS & SCAN",14,true));
    LinearLayout a=new LinearLayout(this);a.setOrientation(0);
    a.addView(btn("+ ADD SOURCE",()->picker(PICK_SOURCE)),new LinearLayout.LayoutParams(0,px(45),1));
    a.addView(btn("SCAN",()->scan()),new LinearLayout.LayoutParams(0,px(45),0.8f));toolbar.addView(a);
    LinearLayout b=new LinearLayout(this);b.setOrientation(0);
    b.addView(btn("FILE TYPES",()->chooseTypes()),new LinearLayout.LayoutParams(0,px(45),1));
    b.addView(btn("+ DESTINATION",()->addBucket()),new LinearLayout.LayoutParams(0,px(45),1));
    b.addView(btn("ABOUT",()->about()),new LinearLayout.LayoutParams(0,px(45),0.65f));toolbar.addView(b);
    if(working)toolbar.addView(label("Scanning / processing. Controls temporarily disabled.",12,false));
  }
  void renderReviewToolbar(){
    toolbar.addView(label("MANUAL REVIEW & MOVE HISTORY",14,true));
    LinearLayout a=new LinearLayout(this);a.setOrientation(0);
    a.addView(btn("CHOOSE REVIEW FOLDER",()->picker(PICK_REVIEW)),new LinearLayout.LayoutParams(0,px(45),1));
    a.addView(btn("SCAN AGAIN",()->scan()),new LinearLayout.LayoutParams(0,px(45),0.75f));toolbar.addView(a);
  }
  void renderBrowseToolbar(){
    String head=tab.equals("COPIES")?"BYTE-IDENTICAL FILE GROUPS":tab.equals("VERSIONS")?"POSSIBLE OLDER REPORT VERSIONS":"ALL SCANNED FILES";
    toolbar.addView(label(head,14,true));
    EditText search=new EditText(this);search.setSingleLine(true);search.setTextSize(14);search.setHint("Search filename or folder…");
    search.setText(query);search.setSelectAllOnFocus(false);
    search.addTextChangedListener(new TextWatcher(){
      public void beforeTextChanged(CharSequence s,int start,int count,int after){}
      public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();page=0;refreshList();}
      public void afterTextChanged(Editable e){}
    });toolbar.addView(search,new LinearLayout.LayoutParams(-1,px(48)));
    LinearLayout a=new LinearLayout(this);a.setOrientation(0);
    Spinner cats=new Spinner(this);String[] kinds={"All","Documents","Photos","Videos","Audio","Archives","Other"};
    cats.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,kinds));
    cats.setSelection(Arrays.asList(kinds).indexOf(filter));
    cats.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onNothingSelected(android.widget.AdapterView<?> parent){}
      public void onItemSelected(android.widget.AdapterView<?> parent,View v,int p,long id){String x=kinds[p];if(!filter.equals(x)){filter=x;page=0;refreshList();}}
    });a.addView(cats,new LinearLayout.LayoutParams(0,px(44),1));
    Spinner order=new Spinner(this);String[] sorts={"Newest","Largest","Name"};order.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,sorts));
    order.setSelection(Arrays.asList(sorts).indexOf(sort));
    order.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onNothingSelected(android.widget.AdapterView<?> parent){}
      public void onItemSelected(android.widget.AdapterView<?> parent,View v,int p,long id){String x=sorts[p];if(!sort.equals(x)){sort=x;page=0;refreshList();}}
    });a.addView(order,new LinearLayout.LayoutParams(0,px(44),1));
    toolbar.addView(a);
  }
  String shortPath(String root){try{return Uri.parse(root).getLastPathSegment();}catch(Exception e){return root;}}
  void refreshList(){
    if(listView==null)return;
    allRows.clear();displayRows.clear();
    if(tab.equals("HOME")){
      addSummary();
      if(roots().isEmpty())allRows.add(new RowItem("No source folders yet","Tap ADD SOURCE. Choose a specific folder via Android's folder picker.",false));
      else for(String r:roots()){RowItem item=new RowItem("SOURCE  ·  "+shortPath(r),"Tap to remove from scan list; no files are deleted.",false);item.key="SOURCE|"+r;allRows.add(item);}
      if(review()!=null)allRows.add(new RowItem("REVIEW DESTINATION  ·  "+shortPath(review()),"Review copies can be restored from the REVIEW tab.",false));
      else allRows.add(new RowItem("REVIEW DESTINATION  ·  Not set","Choose destination from REVIEW tab before setting aside.",false));
      for(Bucket b:buckets()){RowItem item=new RowItem("MOVE DESTINATION  ·  "+b.name,shortPath(b.tree)+"  ·  Tap to remove destination from menu.",false);item.key="BUCKET|"+b.name;allRows.add(item);}
    } else if(tab.equals("REVIEW")){
      ArrayList<FileCopy> h=history();for(FileCopy c:h){
        RowItem r=new RowItem(c.originalName,(c.bucket==null?"Review":c.bucket)+"  ·  "+fmt(c.size)+"  ·  Tap to restore",false);r.h=c;allRows.add(r);
      }
      if(h.isEmpty())allRows.add(new RowItem("No move history","Files set aside through the app will appear here.",false));
    } else if(tab.equals("FILES")){
      for(FileEntry f:files){RowItem r=fileRow(f,false);allRows.add(r);}
    } else if(tab.equals("COPIES")){
      for(ArrayList<FileEntry> g:dupGroups.values()){
        if(g.size()<2)continue;long waste=0;for(int i=1;i<g.size();i++)waste+=g.get(i).size;
        RowItem head=new RowItem("IDENTICAL CONTENT  ·  "+g.size()+" files","SHA-256 verified  ·  "+fmt(waste)+" potential saving",true);allRows.add(head);
        for(FileEntry f:g){RowItem item=fileRow(f,!duplicate.contains(f.key));allRows.add(item);}
      }
    } else if(tab.equals("VERSIONS")){
      for(Map.Entry<String,ArrayList<FileEntry>> e:revGroups.entrySet()){
        ArrayList<FileEntry> g=e.getValue();boolean hasOld=false;for(FileEntry f:g)if(older.contains(f.key))hasOld=true;
        if(!hasOld)continue;
        allRows.add(new RowItem("REPORT FAMILY  ·  "+e.getKey(),"Keep latest 3 recognized revisions  ·  Older versions are suggestions, not confirmed duplicates.",true));
        for(FileEntry f:g)allRows.add(fileRow(f,!older.contains(f.key)));
      }
    }
    String needle=query.trim().toLowerCase(Locale.ROOT);
    ArrayList<RowItem> filtered=new ArrayList<>();
    if(tab.equals("COPIES")||tab.equals("VERSIONS")){
      RowItem pendingHead=null;ArrayList<RowItem> group=new ArrayList<>();
      for(RowItem r:allRows){
        if(r.group){if(!group.isEmpty()){filtered.add(pendingHead);filtered.addAll(group);}pendingHead=r;group=new ArrayList<>();}
        else if(r.f!=null&&match(r,needle))group.add(r);
      }
      if(!group.isEmpty()){filtered.add(pendingHead);filtered.addAll(group);}
    }else{
      for(RowItem r:allRows)if(r.f==null||match(r,needle))filtered.add(r);
      if(tab.equals("FILES")){
        Collections.sort(filtered,(a,b)->{
          if(a.f==null)return 1;if(b.f==null)return -1;
          return sort.equals("Largest")?Long.compare(b.f.size,a.f.size):sort.equals("Name")?a.f.name.compareToIgnoreCase(b.f.name):Long.compare(b.f.modified,a.f.modified);
        });
      }
    }
    int count=filtered.size();int maxPage=Math.max(0,(count-1)/PAGE_SIZE);page=Math.max(0,Math.min(page,maxPage));
    int start=page*PAGE_SIZE,end=Math.min(start+PAGE_SIZE,count);
    for(int i=start;i<end;i++)displayRows.add(filtered.get(i));
    listView.setAdapter(new RowAdapter(displayRows));listView.setOnItemClickListener((p,v,pos,id)->{
      if(pos>=displayRows.size())return;RowItem r=displayRows.get(pos);
      if(r.h!=null){restoreConfirm(r.h);return;}
      if(r.f!=null){if(r.kind!=null&&r.kind.equals("KEEPER")&&(tab.equals("COPIES")||tab.equals("VERSIONS")))return;
        if(selected.contains(r.f.key))selected.remove(r.f.key);else selected.add(r.f.key);refreshList();return;}
      if(r.key!=null&&r.key.startsWith("BUCKET|")){String nm=r.key.substring(7);new AlertDialog.Builder(this).setTitle("Remove destination?").setMessage(nm+" (existing files remain untouched)").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->{ArrayList<Bucket> bs=buckets();bs.removeIf(b->b.name.equals(nm));saveBuckets(bs);refreshList();}).show();}
      if(r.key!=null&&r.key.startsWith("SOURCE|")){String uri=r.key.substring(7);new AlertDialog.Builder(this).setTitle("Remove source folder?").setMessage("This only removes it from the scan list.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->{ArrayList<String> a=roots();a.remove(uri);prefs().edit().putString("roots",new JSONArray(a).toString()).apply();files.clear();dupGroups.clear();revGroups.clear();older.clear();duplicate.clear();selected.clear();refreshList();}).show();}
    });
    if(footer!=null&&!tab.equals("HOME")&&!tab.equals("REVIEW")){
      // Refresh footer pager without rebuilding the search field.
      if(pager!=null&&pager.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)pager.getParent()).removeView(pager);
      while(footer.getChildCount()>3)footer.removeViewAt(0);
      LinearLayout pageRow=new LinearLayout(this);pageRow.setOrientation(0);
      Button prev=btn("◀ PREV",()->{if(page>0){page--;refreshList();listView.setSelection(0);}});
      prev.setEnabled(page>0);pageRow.addView(prev,new LinearLayout.LayoutParams(0,px(40),0.85f));
      pager=label("Page "+(page+1)+"/"+(maxPage+1)+"  ·  "+count+" rows",12,true);pager.setGravity(Gravity.CENTER);
      pager.setOnClickListener(v->jumpPage(maxPage));pageRow.addView(pager,new LinearLayout.LayoutParams(0,px(40),1.6f));
      Button next=btn("NEXT ▶",()->{if(page<maxPage){page++;refreshList();listView.setSelection(0);}});
      next.setEnabled(page<maxPage);pageRow.addView(next,new LinearLayout.LayoutParams(0,px(40),0.85f));
      footer.addView(pageRow,0);
    }
    if(selectionLabel!=null)selectionLabel.setText(selected.size()+" selected");
  }
  boolean match(RowItem r,String needle){
    if(r.f==null)return true;
    if(!filter.equals("All")&&!filter.equals(r.f.category))return false;
    return needle.isEmpty()||(r.f.name+" "+r.f.folder).toLowerCase(Locale.ROOT).contains(needle);
  }
  RowItem fileRow(FileEntry f,boolean protectedCopy){
    RowItem r=new RowItem((duplicate.contains(f.key)?"[EXTRA COPY]  ":older.contains(f.key)?"[OLDER]  ":"")+f.name,
      f.category+"  ·  "+fmt(f.size)+"  ·  "+f.folder+(f.hashKey==null?"":"  ·  SHA-256"),false);
    r.f=f;r.kind=protectedCopy?"KEEPER":"FILE";return r;
  }
  void addSummary(){
    long potential=0,total=0;for(FileEntry f:files){total+=f.size;if(duplicate.contains(f.key)||older.contains(f.key))potential+=f.size;}
    allRows.add(new RowItem(files.size()+" files indexed  ·  "+fmt(total),"Exact extra copies: "+duplicate.size()+"  ·  Older report candidates: "+older.size(),false));
    allRows.add(new RowItem(fmt(potential)+" potential candidates","Estimate only; moving files within the same storage does not free space.",false));
    allRows.add(new RowItem("SHA-256 CONTENT COMPARISON","Find byte-identical photos, videos, audio, documents, archives and other types regardless of filename.",false));
  }
  final class RowAdapter extends BaseAdapter {
    final ArrayList<RowItem> rows;RowAdapter(ArrayList<RowItem> items){rows=new ArrayList<>(items);}
    public int getCount(){return rows.size();}
    public Object getItem(int i){return rows.get(i);}
    public long getItemId(int i){return i;}
    public View getView(int pos,View previous,android.view.ViewGroup parent){
      RowItem r=rows.get(pos);
      LinearLayout box=new LinearLayout(MainActivity.this);box.setOrientation(1);
      box.setPadding(px(13),px(r.group?11:8),px(13),px(9));
      box.setBackgroundColor(r.group?Color.rgb(226,235,250):Color.WHITE);
      LinearLayout titleRow=new LinearLayout(MainActivity.this);titleRow.setOrientation(0);titleRow.setGravity(Gravity.CENTER_VERTICAL);
      if(r.f!=null && !(r.kind!=null&&r.kind.equals("KEEPER")&&(tab.equals("COPIES")||tab.equals("VERSIONS")))){
        CheckBox cb=new CheckBox(MainActivity.this);cb.setButtonTintList(android.content.res.ColorStateList.valueOf(blue));
        cb.setChecked(selected.contains(r.f.key));cb.setOnClickListener(v->{if(cb.isChecked())selected.add(r.f.key);else selected.remove(r.f.key);if(selectionLabel!=null)selectionLabel.setText(selected.size()+" selected");});
        titleRow.addView(cb);
      }
      TextView title=label(r.title,r.group?12:13,true);title.setMaxLines(2);titleRow.addView(title,new LinearLayout.LayoutParams(0,-2,1));
      box.addView(titleRow);
      TextView sub=label(r.sub,r.group?11:11,false);sub.setTextColor(Color.rgb(98,112,137));sub.setMaxLines(2);box.addView(sub);
      if(r.f!=null&&r.kind!=null&&r.kind.equals("KEEPER")){TextView keep=label("PROTECTED / KEPT",10,true);keep.setTextColor(Color.rgb(24,111,99));box.addView(keep);}
      return box;
    }
  }
  void jumpPage(int max){EditText t=new EditText(this);t.setInputType(2);t.setHint("1 to "+(max+1));
    new AlertDialog.Builder(this).setTitle("Jump to page").setView(t).setNegativeButton("Cancel",null).setPositiveButton("Go",(d,w)->{
      try{page=Math.max(0,Math.min(max,Integer.parseInt(t.getText().toString())-1));refreshList();listView.setSelection(0);}catch(Exception ignored){}
    }).show();
  }
  void chooseTypes(){
    boolean[] v=new boolean[categories.length];for(int i=0;i<v.length;i++)v[i]=allowed(categories[i]);
    new AlertDialog.Builder(this).setTitle("Scan file categories").setMultiChoiceItems(categories,v,(d,i,on)->v[i]=on)
      .setMessage(null).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
        boolean any=false;for(int i=0;i<v.length;i++){prefs().edit().putBoolean("type_"+categories[i],v[i]).apply();any|=v[i];}
        if(!any){prefs().edit().putBoolean("type_Documents",true).apply();message="At least one category is required; Documents remains enabled.";}
        else message="File types updated. Re-scan to apply.";
        render();
      }).show();
  }
  void about(){new AlertDialog.Builder(this).setTitle("ReportKeeper • v0.2.0")
    .setMessage("Created by PERSPICAL\nDeveloped by Adam F. Amru\n\nSHA-256 matches byte-for-byte file contents, not names. Similar photos with changed compression are NOT identical.\n\nOlder revisions are estimated from document filenames only; verify before removing.\n\nAll files remain on your device. No account, server, or internet transfer.\n\nAlways test using copies of your files before making changes.")
    .setPositiveButton("OK",null).show();}
  void addBucket(){
    EditText t=new EditText(this);t.setSingleLine();t.setHint("e.g. Project Archive / Photos to Review");
    new AlertDialog.Builder(this).setTitle("Create move destination").setMessage("Give this destination a short name, then choose its folder.").setView(t)
      .setNegativeButton("Cancel",null).setPositiveButton("CHOOSE FOLDER",(d,w)->{
        String n=t.getText().toString().trim();if(n.isEmpty()){message="Destination name cannot be empty.";render();return;}
        pendingBucketName=n;picker(PICK_BUCKET);
      }).show();
  }
  void picker(int code){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,code);}
  @Override protected void onActivityResult(int rc,int result,Intent data){super.onActivityResult(rc,result,data);
    if(result!=RESULT_OK||data==null||data.getData()==null)return;
    Uri uri=data.getData();try{
      int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      getContentResolver().takePersistableUriPermission(uri,flags);
      if(rc==PICK_SOURCE){ArrayList<String> a=roots();if(!a.contains(uri.toString()))a.add(uri.toString());prefs().edit().putString("roots",new JSONArray(a).toString()).apply();}
      if(rc==PICK_REVIEW)prefs().edit().putString("review",uri.toString()).apply();
      if(rc==PICK_BUCKET){ArrayList<Bucket> b=buckets();b.removeIf(k->k.name.equalsIgnoreCase(pendingBucketName));b.add(new Bucket(pendingBucketName,uri.toString()));saveBuckets(b);pendingBucketName="";}
      files.clear();duplicate.clear();older.clear();dupGroups.clear();revGroups.clear();selected.clear();message="Folder selected. Run a new scan.";
    }catch(Exception e){message="Could not access selected folder: "+e.getMessage();}
    render();
  }
  static String category(String name,String mime){String s=name.toLowerCase(Locale.ROOT);String x=mime==null?"":mime.toLowerCase(Locale.ROOT);
    if(x.startsWith("image/")||s.matches(".*\\.(jpg|jpeg|png|heic|heif|webp|gif|bmp|tif|tiff|dng|raw|avif)"))return "Photos";
    if(x.startsWith("video/")||s.matches(".*\\.(mp4|mkv|mov|avi|webm|3gp|m4v|wmv)"))return "Videos";
    if(x.startsWith("audio/")||s.matches(".*\\.(mp3|m4a|aac|flac|ogg|wav|wma|opus|mid|midi)"))return "Audio";
    if(s.matches(".*\\.(zip|rar|7z|tar|gz|bz2|apk|jar|iso|cab)"))return "Archives";
    if(x.startsWith("text/")||s.matches(".*\\.(pdf|doc|docx|xls|xlsx|ppt|pptx|odt|ods|odp|txt|csv|rtf|md|json|xml|epub)"))return "Documents";
    return "Other";
  }
  static boolean isReport(String s){return "Documents".equals(category(s,null));}
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
  void scan(){if(working||roots().isEmpty()){message="Add a source folder first.";render();return;}
    working=true;files.clear();duplicate.clear();older.clear();selected.clear();dupGroups.clear();revGroups.clear();
    render();update("Scanning selected file categories…");
    jobs.submit(()->{
      final ArrayList<String> issues=new ArrayList<>();try{
        final ArrayList<String> deniedTrees=new ArrayList<>();for(Bucket b:buckets())deniedTrees.add(b.tree);
        if(review()!=null)deniedTrees.add(review());
        for(String root:roots()){
          String first=DocumentsContract.getTreeDocumentId(Uri.parse(root));
          ArrayDeque<String[]> q=new ArrayDeque<>();q.add(new String[]{first,""});HashSet<String> seen=new HashSet<>();
          while(!q.isEmpty()){
            String[] cur=q.removeFirst();if(!seen.add(cur[0]))continue;
            if(cur[1].equalsIgnoreCase("/ReportKeeper_Review"))continue;
            boolean ignored=false;for(String tree:deniedTrees)try{
              if(!Uri.parse(root).getAuthority().equals(Uri.parse(tree).getAuthority()))continue;
              String target=DocumentsContract.getTreeDocumentId(Uri.parse(tree));
              if(cur[0].equals(target)||cur[0].startsWith(target+"/")){ignored=true;break;}
            }catch(Exception ex){}
            if(ignored)continue;
            try{children(root,cur[0],(id,name,mime,size,changed)->{
              if(name==null||id==null||name.equalsIgnoreCase("ReportKeeper_Review"))return;
              if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){q.add(new String[]{id,cur[1]+"/"+name});return;}
              String type=category(name,mime);if(!allowed(type))return;
              FileEntry f=new FileEntry();f.tree=root;f.id=id;f.parent=cur[0];f.name=name;f.folder=cur[1];f.mime=mime;
              f.key=root+"|"+id;f.size=size;f.modified=changed;f.category=type;files.add(f);
            });}catch(Exception e){if(issues.size()<100)issues.add("Scan "+cur[1]+": "+e.getMessage());}
            if(files.size()>0&&files.size()%1000==0)update("Indexing "+files.size()+" files…");
          }
        }
        Map<Long,List<FileEntry>> sizes=new HashMap<>();
        for(FileEntry f:files)if(f.size>=0)sizes.computeIfAbsent(f.size,k->new ArrayList<>()).add(f);
        Map<String,ArrayList<FileEntry>> byHash=new HashMap<>();int hashing=0;
        for(List<FileEntry> g:sizes.values())if(g.size()>1)for(FileEntry f:g){
          try{String digest=f.size+":"+hash(f.documentUri());f.hashKey=digest;
            byHash.computeIfAbsent(digest,k->new ArrayList<>()).add(f);
          }catch(Exception e){if(issues.size()<100)issues.add("Hash "+f.name+": "+e.getMessage());}
          if(++hashing%100==0)update("Comparing file signatures: "+hashing+" examined…");
        }
        ArrayList<Map.Entry<String,ArrayList<FileEntry>>> groups=new ArrayList<>(byHash.entrySet());
        groups.sort((a,b)->Long.compare(b.getValue().size()*b.getValue().get(0).size,a.getValue().size()*a.getValue().get(0).size));
        for(Map.Entry<String,ArrayList<FileEntry>> e:groups)if(e.getValue().size()>1){
          ArrayList<FileEntry> g=e.getValue();g.sort((a,b)->Long.compare(b.modified,a.modified));
          dupGroups.put(e.getKey(),g);for(int i=1;i<g.size();i++)duplicate.add(g.get(i).key);
        }
        Map<String,Map<String,ArrayList<FileEntry>>> families=new LinkedHashMap<>();
        for(FileEntry f:files)if("Documents".equals(f.category)){
          String vers=version(f.name);
          // Only explicit revision identifiers are considered; identical copies are handled by SHA-256.
          if(vers.equals("base")||f.name.toLowerCase(Locale.ROOT).matches(".*(contract|agreement|signed|signature).*"))continue;
          String k=family(f.name)+"  ("+f.folder+")";
          families.computeIfAbsent(k,z->new LinkedHashMap<>()).computeIfAbsent(vers,z->new ArrayList<>()).add(f);
        }
        for(Map.Entry<String,Map<String,ArrayList<FileEntry>>> e:families.entrySet()){
          Map<String,ArrayList<FileEntry>> rev=e.getValue();if(rev.size()<=3)continue;
          ArrayList<String> ids=new ArrayList<>(rev.keySet());
          ids.sort((a,b)->Long.compare(rank(b,rev.get(b)),rank(a,rev.get(a))));
          ArrayList<FileEntry> g=new ArrayList<>();
          for(int i=0;i<ids.size();i++){for(FileEntry f:rev.get(ids.get(i))){g.add(f);if(i>=3)older.add(f.key);}}
          revGroups.put(e.getKey(),g);
        }
        String msg="Scan complete: "+files.size()+" files • "+dupGroups.size()+" duplicate groups • "+duplicate.size()+" extra copies • "+older.size()+" old-version candidates.";
        if(!issues.isEmpty())msg+=" "+issues.size()+" read errors (inaccessible files are never marked duplicates).";
        update(msg);
      }catch(Exception e){update("Scan error: "+e.getMessage());}
      finally{runOnUiThread(()->{working=false;refreshList();});}
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
  void confirm(boolean delete){
    ArrayList<FileEntry> chosen=new ArrayList<>();for(FileEntry f:files)if(selected.contains(f.key))chosen.add(f);
    if(chosen.isEmpty()){update("Select files first.");return;}
    if(delete){confirmDo(chosen,true,null,"",false);return;}
    ArrayList<Bucket> b=buckets();if(review()!=null)b.add(0,new Bucket("Manual Review",review()));
    if(b.isEmpty()){new AlertDialog.Builder(this).setTitle("Choose destination").setMessage("Set a manual-review folder or add a move destination from HOME first.").setPositiveButton("OK",null).show();return;}
    String[] choices=new String[b.size()];for(int i=0;i<b.size();i++)choices[i]=b.get(i).name+"  •  "+shortPath(b.get(i).tree);
    new AlertDialog.Builder(this).setTitle("Move "+chosen.size()+" selected files")
      .setItems(choices,(d,which)->{
        Bucket dst=b.get(which);
        final boolean[] sub={false};
        new AlertDialog.Builder(this).setTitle("Destination: "+dst.name)
          .setMessage("Optional organization: put each file into a category subfolder (Photos, Documents, Videos, etc.).")
          .setMultiChoiceItems(new String[]{"Create category subfolders"},sub,(diag,index,on)->sub[0]=on)
          .setNegativeButton("Cancel",null).setPositiveButton("CONTINUE",(dia,w)->confirmDo(chosen,false,dst.tree,dst.name,sub[0]))
          .show();
      }).setNegativeButton("Cancel",null).show();
  }
  void confirmDo(ArrayList<FileEntry> chosen,boolean delete,String destination,String bucket,boolean byCategory){
    long bytes=0;for(FileEntry f:chosen)bytes+=Math.max(0,f.size);
    String text=chosen.size()+" file(s), "+fmt(bytes)+".\n\n"+
      (delete?"DELETE MAY BE PERMANENT. No Recycle Bin is guaranteed. Confirm only files you do not need.":
       "Files are copied, SHA-256-verified, then their originals are deleted. They can be restored from REVIEW history when folder access remains. Moving within a single storage device does not free storage space.");
    new AlertDialog.Builder(this).setTitle(delete?"Confirm permanent deletion":"Confirm verified move").setMessage(text)
      .setNegativeButton("Cancel",null).setPositiveButton(delete?"DELETE FILES":"MOVE FILES",(d,w)->operate(chosen,delete,destination,bucket,byCategory)).show();
  }
  void operate(List<FileEntry> chosen,boolean delete,String destination,String bucket,boolean byCategory){
    working=true;update("Processing "+chosen.size()+" selected files…");int total=chosen.size();
    jobs.submit(()->{int done=0;ArrayList<String> errors=new ArrayList<>();
      for(FileEntry f:chosen)try{
        if(delete){if(!DocumentsContract.deleteDocument(getContentResolver(),f.documentUri()))throw new IOException("Provider refused deletion");}
        else moveVerified(f,destination,bucket,byCategory);
        done++;if(done%10==0)update("Processed "+done+" of "+total+" files…");
      }catch(Exception e){if(errors.size()<20)errors.add(f.name+": "+e.getMessage());}
      final int success=done;
      runOnUiThread(()->{working=false;files.clear();selected.clear();duplicate.clear();older.clear();dupGroups.clear();revGroups.clear();
        message=success+" of "+total+" processed. Run a new scan to refresh file groups.";render();
        new AlertDialog.Builder(this).setTitle("Operation result").setMessage(message+(errors.isEmpty()?"":"\n\nIssues:\n"+String.join("\n",errors.subList(0,Math.min(errors.size(),6)))))
          .setPositiveButton("OK",null).show();});
    });
  }
  String ensureFolder(String tree,String parent,String name)throws Exception{
    final String[] found={null};children(tree,parent,(id,n,m,sz,dt)->{if(name.equals(n)&&DocumentsContract.Document.MIME_TYPE_DIR.equals(m))found[0]=id;});
    if(found[0]!=null)return found[0];
    Uri created=DocumentsContract.createDocument(getContentResolver(),doc(tree,parent),DocumentsContract.Document.MIME_TYPE_DIR,name);
    if(created==null)throw new IOException("Could not create folder "+name);
    return DocumentsContract.getDocumentId(created);
  }
  void moveVerified(FileEntry f,String dest,String bucket,boolean organize)throws Exception{
    if(dest==null)throw new IOException("Missing destination");
    String root=DocumentsContract.getTreeDocumentId(Uri.parse(dest));
    if(f.tree.equals(dest)&&root.equals(f.parent)&&!organize)throw new IOException("Source and destination are same folder");
    String parent=dest.equals(review())?ensureFolder(dest,root,"ReportKeeper_Review"):root;
    if(organize)parent=ensureFolder(dest,parent,f.category);
    // This check prevents a provider from copying then removing the exact same document.
    if(f.tree.equals(dest)&&f.parent.equals(parent))throw new IOException("Destination is the source folder");
    String name=System.currentTimeMillis()+"_"+UUID.randomUUID().toString().substring(0,6)+"_"+f.name.replaceAll("[/\\\\]","_");
    Uri created=DocumentsContract.createDocument(getContentResolver(),doc(dest,parent),f.mime==null?"application/octet-stream":f.mime,name);
    if(created==null)throw new IOException("Provider could not create destination file");
    try{copyVerified(f.documentUri(),created);}catch(Exception e){
      try{DocumentsContract.deleteDocument(getContentResolver(),created);}catch(Exception ignored){}
      throw e;
    }
    FileCopy c=new FileCopy();c.tree=dest;c.doc=DocumentsContract.getDocumentId(created);
    c.sourceTree=f.tree;c.sourceParent=f.parent;c.originalName=f.name;c.reviewName=name;c.size=f.size;c.created=System.currentTimeMillis();c.bucket=bucket;
    if(!DocumentsContract.deleteDocument(getContentResolver(),f.documentUri())){
      try{DocumentsContract.deleteDocument(getContentResolver(),created);}catch(Exception ignored){}
      throw new IOException("Original could not be removed; attempted to remove extra copy.");
    }
    ArrayList<FileCopy> records=history();records.add(0,c);saveHistory(records);
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
