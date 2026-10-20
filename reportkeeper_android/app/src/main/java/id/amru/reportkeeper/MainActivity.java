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
import android.provider.Settings;
import android.os.Environment;
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
  String pendingBucketName=""; boolean globalScan=false, awaitingAllFilesAccess=false;
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
  static int keeperScore(FileEntry f){
    String n=stem(f.name).toLowerCase(Locale.ROOT);
    int points=0;
    if(n.matches(".*(?:\\s|[_-])copy(?:\\s?\\d+)?$"))points-=160;
    if(n.matches(".*\\s*\\(\\d+\\)$"))points-=170;
    if(n.matches(".*(?:[_-]|\\s)\\d{1,2}$"))points-=80;
    if(n.contains("original")||n.contains("master"))points+=130;
    String dir=f.folder==null?"":f.folder.toLowerCase(Locale.ROOT);
    if(dir.contains("camera")||dir.contains("dcim"))points+=60;
    if(dir.contains("download"))points-=10;
    if(dir.contains("copy"))points-=40;
    return points;
  }
  static Comparator<FileEntry> originalFirst(){
    return (a,b)->{int c=Integer.compare(keeperScore(b),keeperScore(a));if(c!=0)return c;
      c=Long.compare(a.modified,b.modified);if(c!=0)return c;
      return a.key.compareTo(b.key);};
  }
  boolean safeCandidate(FileEntry f){return duplicate.contains(f.key)&&!protectedKeepers.contains(f.key);}
  ArrayList<FileEntry> extraCandidates(){
    ArrayList<FileEntry> out=new ArrayList<>();for(FileEntry f:files)if(safeCandidate(f))out.add(f);return out;
  }
  void selectAllExtras(){
    selected.clear();for(FileEntry f:files)if(safeCandidate(f))selected.add(f.key);
    if(selected.isEmpty())new AlertDialog.Builder(this).setTitle("Nothing to clean").setMessage("No extra byte-identical copies were found in the latest scan.").setPositiveButton("OK",null).show();
    if(listView==null)jump("COPIES");else refreshList();
  }
  void autoCleanup(){
    if(working)return;
    ArrayList<FileEntry> extras=extraCandidates();
    if(extras.isEmpty()){new AlertDialog.Builder(this).setTitle("No exact duplicates").setMessage("Scan first, then return here. Only extra copies are eligible for automated cleanup.").setPositiveButton("OK",null).show();return;}
    selected.clear();for(FileEntry f:extras)selected.add(f.key);
    long bytes=0;for(FileEntry f:extras)bytes+=Math.max(0,f.size);
    String detail=extras.size()+" extra copies • "+fmt(bytes)+" potential savings.\\n\\nOne original from EVERY identical group is locked and cannot be deleted.\\n\\nThe app will verify source and protected original hashes again before deletion.\\n\\nNo files are deleted until you explicitly confirm.";
    new AlertDialog.Builder(this).setTitle("Safe Auto Cleanup - Preview")
      .setMessage(detail.replace("\\n","\n"))
      .setNegativeButton("REVIEW FIRST",(d,w)->jump("COPIES"))
      .setNeutralButton("CANCEL",null)
      .setPositiveButton("REVIEW DELETE",(d,w)->confirm(true)).show();
  }

  final ExecutorService jobs=Executors.newSingleThreadExecutor();
  final HashSet<String> selected=new HashSet<>();
  final ArrayList<FileEntry> files=new ArrayList<>();
  final HashSet<String> duplicate=new HashSet<>(), older=new HashSet<>(), protectedKeepers=new HashSet<>();
  LinearLayout screen; TextView status; boolean working=false;
  final int blue=Color.rgb(8,158,255), ink=Color.rgb(240,248,255);
  String message="Choose a source folder to scan. No files are changed during scanning.";
  static final class FileEntry {
    String tree,id,parent,name,folder,mime,key,category,hashKey,path; long size,modified;
    Uri documentUri() { return path==null?DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree),id):Uri.fromFile(new File(path)); }
  }
  static final class FileCopy {
    String tree,doc,sourceTree,sourceParent,originalName,reviewName,bucket,sourcePath; long size,created;
    JSONObject json() throws JSONException { JSONObject j=new JSONObject();
      j.put("tree",tree);j.put("doc",doc);j.put("sourceTree",sourceTree);j.put("sourceParent",sourceParent);j.put("sourcePath",sourcePath==null?"":sourcePath);
      j.put("originalName",originalName);j.put("reviewName",reviewName);j.put("bucket",bucket==null?"Review":bucket);j.put("size",size);j.put("created",created); return j; }
    static FileCopy parse(JSONObject j) throws JSONException {FileCopy f=new FileCopy();
      f.tree=j.getString("tree");f.doc=j.getString("doc");f.sourceTree=j.getString("sourceTree");
      f.sourceParent=j.getString("sourceParent");f.sourcePath=j.optString("sourcePath","");f.originalName=j.getString("originalName");
      f.reviewName=j.getString("reviewName");f.bucket=j.optString("bucket","Review");f.size=j.getLong("size");f.created=j.getLong("created");return f;}
  }
  android.content.SharedPreferences prefs(){return getSharedPreferences("rk",MODE_PRIVATE);}
  JSONArray array(String key){try{return new JSONArray(prefs().getString(key,"[]"));}catch(Exception e){return new JSONArray();}}
  ArrayList<String> roots(){ArrayList<String> r=new ArrayList<>();JSONArray a=array("roots");for(int i=0;i<a.length();i++)r.add(a.optString(i));return r;}
  String review(){return prefs().getString("review",null);}
  void update(String text){runOnUiThread(()->{message=text;if(info!=null)info.setText(text);});}
  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    getWindow().setStatusBarColor(Color.rgb(4,14,38));getWindow().setNavigationBarColor(Color.rgb(4,14,38));
    if(!prefs().getBoolean("duplicutter_welcome_v1",false))showWelcome();else render();
  }
  @Override public void onDestroy(){jobs.shutdownNow();super.onDestroy();}
  int px(int d){return (int)(d*getResources().getDisplayMetrics().density+0.5f);}
  TextView label(String s,int sz,boolean strong){
    TextView t=new TextView(this);t.setText(s);t.setTextSize(sz);t.setTextColor(ink);
    if(strong)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
    t.setPadding(px(3),px(5),px(3),px(5));return t;
  }
  android.graphics.drawable.GradientDrawable gradient(int a,int b,int radius,int stroke){
    android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable(
      android.graphics.drawable.GradientDrawable.Orientation.TL_BR,new int[]{a,b});
    d.setCornerRadius(px(radius));
    if(stroke!=0)d.setStroke(px(1),stroke);
    return d;
  }
  int C(String value){return Color.parseColor(value);}
  android.graphics.drawable.GradientDrawable glass(){return gradient(C("#104190"),C("#071A40"),23,C("#2974C2"));}
  android.graphics.drawable.GradientDrawable bright(){return gradient(C("#39E6FF"),C("#045AF5"),22,C("#88F8FF"));}
  android.graphics.drawable.GradientDrawable subtle(){return gradient(C("#123C81"),C("#09224F"),16,C("#255BA0"));}
  Button btn(String name,Runnable click){
    Button b=new Button(this);b.setAllCaps(false);b.setText(name);b.setTextSize(12);
    b.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
    b.setTextColor(Color.WHITE);
    b.setPadding(px(9),px(5),px(9),px(5));
    if(name.contains("DELETE"))b.setBackground(gradient(C("#CE285A"),C("#82133C"),18,C("#FB789A")));
    else if(name.contains("SCAN")||name.contains("MOVE")||name.contains("START")||name.contains("GET STARTED"))b.setBackground(bright());
    else b.setBackground(subtle());
    b.setMinHeight(px(44));b.setOnClickListener(v->click.run());b.setEnabled(!working);return b;
  }
  LinearLayout panel(){
    LinearLayout p=new LinearLayout(this);p.setOrientation(1);p.setBackground(glass());
    p.setPadding(px(13),px(12),px(13),px(12));return p;
  }
  LinearLayout.LayoutParams margins(int w,int h,int t,int b){
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(w,h);
    lp.setMargins(px(1),px(t),px(1),px(b));return lp;
  }
  ImageView iconImage(int size){
    ImageView v=new ImageView(this);v.setImageResource(R.drawable.ic_duplicutter);
    v.setScaleType(ImageView.ScaleType.FIT_CENTER);
    v.setLayoutParams(new LinearLayout.LayoutParams(px(size),px(size)));return v;
  }
  void sectionHeader(LinearLayout p,String heading,String hint){
    TextView h=label(heading,17,true);p.addView(h);
    if(hint!=null){TextView d=label(hint,12,false);d.setTextColor(C("#A8CBED"));p.addView(d);}
  }
  void coloredLine(LinearLayout p,String text,Runnable action){
    TextView t=label(text,12,false);t.setTextColor(C("#DAEEFF"));
    t.setPadding(px(10),px(10),px(10),px(10));t.setBackground(subtle());
    p.addView(t,margins(-1,-2,4,3));if(action!=null)t.setOnClickListener(v->action.run());
  }
  void jump(String where){tab=where;query="";page=0;filter="All";selected.clear();render();}
  void showWelcome(){
    ScrollView sc=new ScrollView(this);sc.setFillViewport(true);
    LinearLayout wrap=new LinearLayout(this);wrap.setPadding(px(24),px(35),px(24),px(30));
    wrap.setOrientation(1);wrap.setGravity(Gravity.CENTER_HORIZONTAL);
    wrap.setBackground(gradient(C("#0757D1"),C("#031233"),0,0));sc.addView(wrap);
    wrap.addView(iconImage(154));gap(wrap,12);
    TextView title=label("Duplicutter",34,true);title.setGravity(Gravity.CENTER);wrap.addView(title);
    TextView sub=label("Smart duplicate finder & cleanup",15,false);
    sub.setTextColor(C("#9FDFFF"));sub.setGravity(Gravity.CENTER);wrap.addView(sub);
    gap(wrap,22);
    LinearLayout features=panel();wrap.addView(features,margins(-1,-2,6,10));
    coloredLine(features,"▣   Find exact duplicates\n       Photos, videos, documents and more",null);
    coloredLine(features,"✓   Safe review\n       You stay in control, always",null);
    coloredLine(features,"▤   Organized cleanup\n       Move files into folders you choose",null);
    gap(wrap,9);
    Button go=btn("GET STARTED  →",()->{prefs().edit().putBoolean("duplicutter_welcome_v1",true).apply();render();});
    wrap.addView(go,new LinearLayout.LayoutParams(-1,px(54)));
    gap(wrap,8);
    Button folders=btn("CHOOSE FOLDERS",()->{prefs().edit().putBoolean("duplicutter_welcome_v1",true).apply();render();picker(PICK_SOURCE);});
    wrap.addView(folders,new LinearLayout.LayoutParams(-1,px(50)));
    gap(wrap,13);
    TextView credit=label("Created by PERSPICAL\nDeveloped by Adam F. Amru",11,false);
    credit.setGravity(Gravity.CENTER);credit.setTextColor(C("#9BB7DD"));wrap.addView(credit);
    setContentView(sc);
  }
    void gap(LinearLayout c,int h){View v=new View(this);c.addView(v,new LinearLayout.LayoutParams(1,px(h)));}
  void render(){
    root=new LinearLayout(this);root.setOrientation(1);root.setBackground(
      gradient(C("#09285B"),C("#03112E"),0,0));
    top=new LinearLayout(this);top.setOrientation(1);
    top.setPadding(px(14),px(8),px(14),px(6));
    LinearLayout banner=new LinearLayout(this);banner.setGravity(Gravity.CENTER_VERTICAL);
    banner.setOrientation(0);banner.addView(iconImage(50));
    LinearLayout names=new LinearLayout(this);names.setOrientation(1);
    LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1);np.leftMargin=px(10);
    TextView name=label("Duplicutter",23,true);names.addView(name);
    TextView slogan=label("Find. Remove. Free up space.",12,false);
    slogan.setTextColor(C("#B4D6FF"));names.addView(slogan);banner.addView(names,np);
    TextView settings=label("⚙",25,false);settings.setGravity(Gravity.CENTER);
    settings.setOnClickListener(v->about());banner.addView(settings,new LinearLayout.LayoutParams(px(44),px(48)));
    top.addView(banner);
    info=label(message,11,false);info.setTextColor(C("#A5CFFF"));info.setMaxLines(2);top.addView(info);
    root.addView(top);
    HorizontalScrollView tabs=new HorizontalScrollView(this);tabs.setHorizontalScrollBarEnabled(false);
    nav=new LinearLayout(this);nav.setOrientation(0);nav.setPadding(px(8),px(4),px(8),px(8));
    String[] namesTabs={"HOME","COPIES","VERSIONS","FILES","REVIEW"};
    for(String t:namesTabs){TextView item=label(t,12,tab.equals(t));item.setGravity(Gravity.CENTER);
      item.setTextColor(Color.WHITE);item.setPadding(px(15),px(11),px(15),px(11));
      item.setBackground(tab.equals(t)?bright():subtle());
      LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMargins(px(3),0,px(3),0);
      nav.addView(item,lp);item.setOnClickListener(v->jump(t));
    }
    tabs.addView(nav);root.addView(tabs);
    listView=null;selectionLabel=null;pager=null;
    if(tab.equals("HOME")){
      ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
      LinearLayout home=new LinearLayout(this);home.setOrientation(1);
      home.setPadding(px(14),px(5),px(14),px(15));scroll.addView(home);
      buildDashboard(home);
      root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
      TextView hint=label("OFFLINE  •  SHA-256 VERIFIED  •  YOU CONTROL DELETION",10,true);
      hint.setTextColor(C("#9CC6FF"));hint.setGravity(Gravity.CENTER);root.addView(hint);
      setContentView(root);return;
    }
    toolbar=new LinearLayout(this);toolbar.setOrientation(1);
    toolbar.setPadding(px(12),px(3),px(12),px(7));root.addView(toolbar);
    if(tab.equals("REVIEW"))renderReviewToolbar();else renderBrowseToolbar();
    listView=new ListView(this);listView.setDividerHeight(px(3));
    listView.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    listView.setBackgroundColor(Color.TRANSPARENT);
    listView.setCacheColorHint(Color.TRANSPARENT);listView.setChoiceMode(ListView.CHOICE_MODE_NONE);
    root.addView(listView,new LinearLayout.LayoutParams(-1,0,1));
    footer=new LinearLayout(this);footer.setOrientation(1);footer.setPadding(px(12),px(5),px(12),px(7));
    footer.setBackground(gradient(C("#0E3678"),C("#061637"),15,C("#2765C2")));
    root.addView(footer);
    if(tab.equals("REVIEW")){
      TextView note=label("Review history  •  Tap an item to restore",12,false);footer.addView(note);
    }else{
      selectionLabel=label(selected.size()+" selected",12,true);
      footer.addView(selectionLabel);
      LinearLayout buttons=new LinearLayout(this);buttons.setOrientation(0);
      buttons.addView(btn("SELECT",()->selectScope()),new LinearLayout.LayoutParams(0,px(47),1));
      buttons.addView(btn("CLEAR",()->{selected.clear();refreshList();}),new LinearLayout.LayoutParams(0,px(47),0.67f));
      footer.addView(buttons);
      LinearLayout ops=new LinearLayout(this);ops.setOrientation(0);
      ops.addView(btn("MOVE / REVIEW",()->confirm(false)),new LinearLayout.LayoutParams(0,px(48),1));
      ops.addView(btn("DELETE",()->confirm(true)),new LinearLayout.LayoutParams(0,px(48),0.65f));
      footer.addView(ops);
    }
    setContentView(root);refreshList();
  }
  void buildDashboard(LinearLayout home){
    LinearLayout sources=panel();home.addView(sources,margins(-1,-2,3,10));
    sectionHeader(sources,"Source Folders","Choose folders to scan for duplicates.");
    LinearLayout buttons=new LinearLayout(this);buttons.setOrientation(0);
    buttons.addView(btn("+ ADD SOURCE",()->picker(PICK_SOURCE)),new LinearLayout.LayoutParams(0,px(47),1));
    sources.addView(buttons);
    if(roots().isEmpty())coloredLine(sources,"No source folders selected yet.",null);
    for(String r:roots())coloredLine(sources,"▣  "+shortPath(r)+"   ·   Tap to remove",()->{
      new AlertDialog.Builder(this).setTitle("Remove source folder?")
      .setMessage("The folder and its contents will not be deleted.")
      .setNegativeButton("Cancel",null).setPositiveButton("REMOVE",(di,w)->{
        ArrayList<String> a=roots();a.remove(r);prefs().edit().putString("roots",new JSONArray(a).toString()).apply();
        files.clear();dupGroups.clear();revGroups.clear();protectedKeepers.clear();selected.clear();older.clear();duplicate.clear();render();
      }).show();
    });
    LinearLayout dest=panel();home.addView(dest,margins(-1,-2,3,10));
    sectionHeader(dest,"Destinations","Organize files into multiple folders.");
    dest.addView(btn("+ ADD DESTINATION",()->addBucket()),new LinearLayout.LayoutParams(-1,px(45)));
    dest.addView(btn("CHOOSE MANUAL REVIEW FOLDER",()->picker(PICK_REVIEW)),new LinearLayout.LayoutParams(-1,px(45)));
    if(review()!=null)coloredLine(dest,"▣  Manual Review · "+shortPath(review()),()->jump("REVIEW"));
    for(Bucket b:buckets())coloredLine(dest,"▣  "+b.name+"  ·  "+shortPath(b.tree),null);
    LinearLayout kinds=panel();home.addView(kinds,margins(-1,-2,3,10));
    sectionHeader(kinds,"Scan File Types","Select which categories to include.");
    LinearLayout.LayoutParams typelp=new LinearLayout.LayoutParams(-1,px(45));
    kinds.addView(btn("CUSTOMIZE FILE TYPES",()->chooseTypes()),typelp);
    TextView chosen=label("",12,false);StringJoiner join=new StringJoiner("   •   ");
    for(String k:categories)if(allowed(k))join.add(k);
    chosen.setText(join.toString());chosen.setTextColor(C("#B1DEFF"));kinds.addView(chosen);
    LinearLayout summary=panel();home.addView(summary,margins(-1,-2,3,10));
    sectionHeader(summary,"Storage Summary","Results from your latest scan");
    long potential=0,total=0;for(FileEntry f:files){total+=Math.max(0,f.size);if(duplicate.contains(f.key)||older.contains(f.key))potential+=Math.max(0,f.size);}
    LinearLayout row=new LinearLayout(this);row.setOrientation(0);
    row.addView(metric(String.valueOf(files.size()),"Files indexed"),new LinearLayout.LayoutParams(0,-2,1));
    row.addView(metric(String.valueOf(dupGroups.size()),"Duplicate groups"),new LinearLayout.LayoutParams(0,-2,1));
    summary.addView(row);
    LinearLayout next=new LinearLayout(this);next.setOrientation(0);
    next.addView(metric(fmt(potential),"Candidates"),new LinearLayout.LayoutParams(0,-2,1));
    next.addView(metric(String.valueOf(older.size()),"Old revisions"),new LinearLayout.LayoutParams(0,-2,1));
    summary.addView(next);
    LinearLayout quick=panel();home.addView(quick,margins(-1,-2,3,11));
    sectionHeader(quick,"Quick Actions","Find and manage duplicates in seconds.");
    quick.addView(btn("✦  SCAN NOW",()->scan()),new LinearLayout.LayoutParams(-1,px(56)));
    quick.addView(btn("VIEW EXACT COPIES  →",()->jump("COPIES")),new LinearLayout.LayoutParams(-1,px(50)));
    quick.addView(btn("MOVE & ORGANIZE  →",()->jump("REVIEW")),new LinearLayout.LayoutParams(-1,px(50)));
  }
  LinearLayout metric(String value,String title){
    LinearLayout holder=new LinearLayout(this);holder.setOrientation(1);holder.setGravity(Gravity.CENTER);
    holder.setPadding(px(4),px(10),px(4),px(10));holder.setBackground(subtle());
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(px(3),px(4),px(3),px(2));
    TextView count=label(value,20,true);count.setTextColor(C("#8BEAFF"));count.setGravity(Gravity.CENTER);holder.addView(count);
    TextView name=label(title,11,false);name.setTextColor(C("#BEDAFF"));name.setGravity(Gravity.CENTER);
    holder.addView(name);LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(1);wrap.addView(holder,lp);return wrap;
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
    TextView h=label("Move & Organize",19,true);toolbar.addView(h);
    TextView sub=label("Review transfers and restore individual files.",12,false);sub.setTextColor(C("#ACCFF0"));toolbar.addView(sub);
    LinearLayout line=new LinearLayout(this);line.setOrientation(0);
    line.addView(btn("REVIEW FOLDER",()->picker(PICK_REVIEW)),new LinearLayout.LayoutParams(0,px(46),1));
    line.addView(btn("ADD DESTINATION",()->addBucket()),new LinearLayout.LayoutParams(0,px(46),1));
    toolbar.addView(line);
  }
    ArrayAdapter<String> menuItems(String[] options){
    return new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,options){
      @Override public View getView(int index,View reuse,android.view.ViewGroup parent){
        TextView t=label(options[index],13,true);t.setTextColor(Color.WHITE);
        t.setPadding(px(13),px(11),px(13),px(11));return t;
      }
      @Override public View getDropDownView(int index,View reuse,android.view.ViewGroup parent){
        TextView t=label(options[index],13,false);t.setTextColor(Color.WHITE);
        t.setBackgroundColor(C("#12366A"));t.setPadding(px(16),px(12),px(16),px(12));return t;
      }
    };
  }
  void renderBrowseToolbar(){
    String head=tab.equals("COPIES")?"Exact Duplicate Finder":
      tab.equals("VERSIONS")?"Document Versions":"Browse All Files";
    TextView title=label(head,19,true);toolbar.addView(title);
    TextView expl=label(tab.equals("COPIES")?"Byte-identical content, verified by SHA-256":
      tab.equals("VERSIONS")?"Keep the latest 3 recognized revisions":"Search and organize your indexed files",11,false);
    expl.setTextColor(C("#A4CEFB"));toolbar.addView(expl);
    EditText search=new EditText(this);search.setSingleLine(true);search.setTextSize(14);
    search.setTextColor(Color.WHITE);search.setHintTextColor(C("#9CC6EB"));
    search.setHint("⌕  Search file names or locations…");search.setPadding(px(15),0,px(14),0);
    search.setBackground(subtle());search.setText(query);
    search.addTextChangedListener(new TextWatcher(){
      public void beforeTextChanged(CharSequence text,int start,int count,int after){}
      public void onTextChanged(CharSequence text,int start,int before,int count){
        query=text.toString();page=0;refreshList();
      }
      public void afterTextChanged(Editable e){}
    });
    toolbar.addView(search,margins(-1,px(48),6,5));
    LinearLayout options=new LinearLayout(this);options.setOrientation(0);
    Spinner type=new Spinner(this);
    String[] categoriesFilter={"All","Photos","Documents","Videos","Audio","Archives","Other"};
    type.setAdapter(menuItems(categoriesFilter));type.setPopupBackgroundDrawable(subtle());
    int choice=Arrays.asList(categoriesFilter).indexOf(filter);type.setSelection(Math.max(0,choice));
    type.setBackground(subtle());
    type.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onNothingSelected(android.widget.AdapterView<?> p){}
      public void onItemSelected(android.widget.AdapterView<?> p,View v,int n,long id){
        if(!filter.equals(categoriesFilter[n])){filter=categoriesFilter[n];page=0;refreshList();}
      }
    });
    LinearLayout.LayoutParams tlp=new LinearLayout.LayoutParams(0,px(47),1);tlp.rightMargin=px(5);options.addView(type,tlp);
    Spinner by=new Spinner(this);String[] order={"Newest","Largest","Name"};
    by.setAdapter(menuItems(order));by.setPopupBackgroundDrawable(subtle());by.setBackground(subtle());
    by.setSelection(Math.max(0,Arrays.asList(order).indexOf(sort)));
    by.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onNothingSelected(android.widget.AdapterView<?> p){}
      public void onItemSelected(android.widget.AdapterView<?> p,View v,int n,long id){
        if(!sort.equals(order[n])){sort=order[n];page=0;refreshList();}
      }
    });
    options.addView(by,new LinearLayout.LayoutParams(0,px(47),1));toolbar.addView(options);
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
        for(FileEntry f:g){RowItem item=fileRow(f,!safeCandidate(f));allRows.add(item);}
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
    int oldFirst=listView.getFirstVisiblePosition();View oldTop=listView.getChildAt(0);int oldOffset=oldTop==null?0:oldTop.getTop();
    listView.setAdapter(new RowAdapter(displayRows));if(oldFirst>0)listView.setSelectionFromTop(oldFirst,oldOffset);listView.setOnItemClickListener((p,v,pos,id)->{
      if(pos>=displayRows.size())return;RowItem r=displayRows.get(pos);
      if(r.h!=null){restoreConfirm(r.h);return;}
      if(r.f!=null){if(!safeCandidate(r.f) && (tab.equals("COPIES")||tab.equals("FILES"))) {showDetails(r.f);return;}if(r.kind!=null&&r.kind.equals("KEEPER")&&(tab.equals("VERSIONS")))return;
        if(selected.contains(r.f.key))selected.remove(r.f.key);else selected.add(r.f.key);refreshList();return;}
      if(r.key!=null&&r.key.startsWith("BUCKET|")){String nm=r.key.substring(7);new AlertDialog.Builder(this).setTitle("Remove destination?").setMessage(nm+" (existing files remain untouched)").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->{ArrayList<Bucket> bs=buckets();bs.removeIf(b->b.name.equals(nm));saveBuckets(bs);refreshList();}).show();}
      if(r.key!=null&&r.key.startsWith("SOURCE|")){String uri=r.key.substring(7);new AlertDialog.Builder(this).setTitle("Remove source folder?").setMessage("This only removes it from the scan list.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->{ArrayList<String> a=roots();a.remove(uri);prefs().edit().putString("roots",new JSONArray(a).toString()).apply();files.clear();dupGroups.clear();revGroups.clear();older.clear();duplicate.clear();selected.clear();refreshList();}).show();}
    });
    listView.setOnItemLongClickListener((parent,view,pos,id)->{
      if(pos<0||pos>=displayRows.size())return false;
      RowItem r=displayRows.get(pos);if(r.f==null)return false;
      try{
        Intent open=new Intent(Intent.ACTION_VIEW);
        open.setDataAndType(r.f.documentUri(),r.f.mime==null?"application/octet-stream":r.f.mime);
        open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(open,"Open file in"));
      }catch(Exception ex){new AlertDialog.Builder(this).setMessage("No compatible app to preview this file.").setPositiveButton("OK",null).show();}
      return true;
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
  void selectScope(){selectAllExtras();}
  void showDetails(FileEntry f){
    StringBuilder b=new StringBuilder();
    b.append("Name: ").append(f.name).append("\n\n");
    b.append("Type: ").append(f.category).append("\n");
    b.append("Size: ").append(fmt(f.size)).append(" (").append(f.size).append(" bytes)\n");
    b.append("Folder: ").append(f.path==null?f.folder:new File(f.path).getParent()).append("\n");
    b.append("Modified: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new java.util.Date(f.modified))).append("\n");
    b.append("Role: ").append(safeCandidate(f)?"EXTRA COPY (eligible for cleanup)":"KEEP / NOT ELIGIBLE FOR DELETE").append("\n");
    b.append("SHA-256: ").append(f.hashKey==null?"not hashed (no same-size candidate)":f.hashKey.substring(f.hashKey.indexOf(':')+1));
    new AlertDialog.Builder(this).setTitle("File Details").setMessage(b.toString())
      .setPositiveButton("CLOSE",null).show();
  }
    boolean match(RowItem r,String needle){
    if(r.f==null)return true;
    if(!filter.equals("All")&&!filter.equals(r.f.category))return false;
    return needle.isEmpty()||(r.f.name+" "+r.f.folder).toLowerCase(Locale.ROOT).contains(needle);
  }
  RowItem fileRow(FileEntry f,boolean protectedCopy){
    RowItem r=new RowItem((duplicate.contains(f.key)?"[EXTRA COPY]  ":older.contains(f.key)?"[OLDER]  ":"")+f.name,
      f.category+"  ·  "+fmt(f.size)+"  ·  "+f.folder+(f.hashKey==null?"":"  ·  SHA-256 "+f.hashKey.substring(f.hashKey.indexOf(":")+1,Math.min(f.hashKey.length(),f.hashKey.indexOf(":")+13))),false);
    r.f=f;r.kind=protectedCopy?"KEEPER":"FILE";return r;
  }
  void addSummary(){
    long potential=0,total=0;for(FileEntry f:files){total+=f.size;if(duplicate.contains(f.key)||older.contains(f.key))potential+=f.size;}
    allRows.add(new RowItem(files.size()+" files indexed  ·  "+fmt(total),"Exact extra copies: "+duplicate.size()+"  ·  Older report candidates: "+older.size(),false));
    allRows.add(new RowItem(fmt(potential)+" potential candidates","Estimate only; moving files within the same storage does not free space.",false));
    allRows.add(new RowItem("SHA-256 CONTENT COMPARISON","Find byte-identical photos, videos, audio, documents, archives and other types regardless of filename.",false));
  }
  final class RowAdapter extends BaseAdapter {
    final ArrayList<RowItem> rows;
    RowAdapter(ArrayList<RowItem> items){rows=new ArrayList<>(items);}
    public int getCount(){return rows.size();}
    public Object getItem(int n){return rows.get(n);}
    public long getItemId(int n){return n;}
    public View getView(int pos,View previous,android.view.ViewGroup parent){
      RowItem r=rows.get(pos);
      LinearLayout wrapper=new LinearLayout(MainActivity.this);
      wrapper.setOrientation(1);wrapper.setPadding(px(12),px(3),px(12),px(3));
      LinearLayout card=new LinearLayout(MainActivity.this);card.setOrientation(1);
      card.setPadding(px(12),px(r.group?9:8),px(12),px(8));
      boolean checked=r.f!=null&&selected.contains(r.f.key);
      card.setBackground(r.group?gradient(C("#1263B7"),C("#0A357A"),19,C("#3198EB")):
        checked?gradient(C("#1762AA"),C("#0D3474"),17,C("#3CCAFD")):subtle());
      LinearLayout line=new LinearLayout(MainActivity.this);line.setOrientation(0);
      line.setGravity(Gravity.CENTER_VERTICAL);
      if(r.f!=null && !(r.kind!=null&&r.kind.equals("KEEPER")&&(tab.equals("COPIES")||tab.equals("VERSIONS")))){
        CheckBox select=new CheckBox(MainActivity.this);
        select.setButtonTintList(android.content.res.ColorStateList.valueOf(C("#4DDFFF")));
        select.setChecked(checked);
        select.setOnClickListener(v->{
          if(select.isChecked())selected.add(r.f.key);else selected.remove(r.f.key);
          if(selectionLabel!=null)selectionLabel.setText(selected.size()+" selected");
        });
        line.addView(select);
      }
      if(r.f!=null){
        String cat=r.f.category;
        String mark="▣";
        if("Photos".equals(cat))mark="▧";else if("Videos".equals(cat))mark="▶";
        else if("Audio".equals(cat))mark="♫";else if("Archives".equals(cat))mark="▤";
        TextView glyph=label(mark,21,true);glyph.setGravity(Gravity.CENTER);
        glyph.setTextColor(C("#AAEDFF"));glyph.setBackground(gradient(C("#1158C2"),C("#093075"),13,C("#278FEA")));
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(px(43),px(43));gp.rightMargin=px(9);
        line.addView(glyph,gp);
      }else if(r.h!=null){
        TextView glyph=label("↶",21,true);glyph.setTextColor(C("#58E6DD"));line.addView(glyph);
      }
      LinearLayout content=new LinearLayout(MainActivity.this);content.setOrientation(1);
      TextView heading=label(r.title,r.group?14:13,true);
      heading.setMaxLines(2);content.addView(heading);
      TextView desc=label(r.sub,11,false);
      desc.setTextColor(C("#B2D2F6"));desc.setMaxLines(2);content.addView(desc);
      if(r.f!=null&&r.kind!=null&&r.kind.equals("KEEPER")){
        TextView keep=label("KEEP ORIGINAL / LATEST",10,true);keep.setTextColor(C("#61E8D4"));content.addView(keep);
      }
      line.addView(content,new LinearLayout.LayoutParams(0,-2,1));card.addView(line);
      wrapper.addView(card,new LinearLayout.LayoutParams(-1,-2));return wrapper;
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
  void about(){new AlertDialog.Builder(this).setTitle("Duplicutter • v0.3.0")
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
  boolean allFilesGranted(){return Build.VERSION.SDK_INT>=30&&Environment.isExternalStorageManager();}
  void scanAll(){
    if(Build.VERSION.SDK_INT<30){
      new AlertDialog.Builder(this).setTitle("Choose folders")
        .setMessage("On this Android version, choose folders in the file picker. Scan All Storage requires Android 11 or newer and explicit special access.")
        .setPositiveButton("CHOOSE FOLDER",(d,w)->picker(PICK_SOURCE)).setNegativeButton("CANCEL",null).show();return;
    }
    if(!allFilesGranted()){
      awaitingAllFilesAccess=true;
      new AlertDialog.Builder(this).setTitle("Allow all-files access?")
        .setMessage("Android requires your approval for a full scan, including Downloads. Duplicutter will inspect locally stored files on primary shared storage. Protected Android/data and system areas remain restricted. Nothing is deleted automatically.")
        .setNegativeButton("NOT NOW",(d,w)->awaitingAllFilesAccess=false)
        .setPositiveButton("OPEN SETTINGS",(d,w)->{
          try{Intent intent=new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName()));startActivity(intent);}
          catch(Exception e){try{startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));}
          catch(Exception err){update("Android settings could not be opened.");}}
        }).show();return;
    }
    scan(true);
  }
  @Override public void onResume(){super.onResume();
    if(awaitingAllFilesAccess&&allFilesGranted()){awaitingAllFilesAccess=false;
      if(root!=null)root.postDelayed(()->scan(true),200);}
  }
  void scan(){scan(false);}
  void scan(boolean all){if(working||(!all&&roots().isEmpty())){message="Select a folder or choose SCAN ALL STORAGE.";render();return;}
    globalScan=all;
    working=true;files.clear();duplicate.clear();older.clear();selected.clear();dupGroups.clear();revGroups.clear();
    render();update("Scanning selected file categories…");
    jobs.submit(()->{
      final ArrayList<String> issues=new ArrayList<>();try{
        final ArrayList<String> deniedTrees=new ArrayList<>();for(Bucket b:buckets())deniedTrees.add(b.tree);
        if(review()!=null)deniedTrees.add(review());
        final HashSet<String> physicalFiles=new HashSet<>();
        if(all) indexSharedStorage(physicalFiles,deniedTrees,issues);
        if(!all)for(String root:roots()){
          String first=DocumentsContract.getTreeDocumentId(Uri.parse(root));
          ArrayDeque<String[]> q=new ArrayDeque<>();q.add(new String[]{first,""});HashSet<String> seen=new HashSet<>();
          while(!q.isEmpty()){
            String[] cur=q.removeFirst();if(!seen.add(cur[0]))continue;
            if(cur[1].equalsIgnoreCase("/ReportKeeper_Review"))continue;
            boolean ignored=false;for(String tree:deniedTrees)try{
              if(!Uri.parse(root).getAuthority().equals(Uri.parse(tree).getAuthority()))continue;
              String target=DocumentsContract.getTreeDocumentId(Uri.parse(tree));
              if(!target.equals(first) && (cur[0].equals(target)||cur[0].startsWith(target+"/"))){ignored=true;break;}
            }catch(Exception ex){}
            if(ignored)continue;
            try{children(root,cur[0],(id,name,mime,size,changed)->{
              if(name==null||id==null||name.equalsIgnoreCase("ReportKeeper_Review"))return;
              if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){q.add(new String[]{id,cur[1]+"/"+name});return;}
              String type=category(name,mime);if(!allowed(type))return;
              if(!physicalFiles.add(Uri.parse(root).getAuthority()+"|"+id))return;
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
          ArrayList<FileEntry> g=e.getValue();g.sort(originalFirst());
          dupGroups.put(e.getKey(),g);protectedKeepers.add(g.get(0).key);
          for(int i=1;i<g.size();i++)duplicate.add(g.get(i).key);
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
      finally{runOnUiThread(()->{working=false;render();});}
    });
  }
  void indexSharedStorage(HashSet<String> visitedFiles,List<String> exclusions,List<String> issues){
    File base=Environment.getExternalStorageDirectory();
    if(base==null||!base.isDirectory()){issues.add("Primary storage is not available");return;}
    HashSet<String> denied=new HashSet<>();
    for(String link:exclusions)try{
      String treeId=DocumentsContract.getTreeDocumentId(Uri.parse(link));
      if(treeId!=null&&treeId.startsWith("primary:")){
        String rel=treeId.substring("primary:".length());
        denied.add(new File(base,rel).getCanonicalPath());
      }
    }catch(Exception ignored){}
    ArrayDeque<File> folders=new ArrayDeque<>();folders.add(base);HashSet<String> seen=new HashSet<>();
    while(!folders.isEmpty()){
      File directory=folders.removeFirst();String real;
      try{real=directory.getCanonicalPath();}catch(Exception e){continue;}
      if(!seen.add(real)||denied.contains(real))continue;
      String lower=directory.getName().toLowerCase(Locale.ROOT);
      if(lower.equals("android")||lower.equals(".thumbnails")||lower.equals("reportkeeper_review")||
         lower.equals("duplicutter_review")||lower.equals(".trash")||lower.equals(".trashed")||lower.equals("lost.dir"))continue;
      File[] children=directory.listFiles();if(children==null)continue;
      for(File c:children){
        try{
          if(c.isDirectory()){if(!c.isHidden())folders.add(c);continue;}
          if(!c.isFile())continue;
          String type=category(c.getName(),null);if(!allowed(type))continue;
          String absolute=c.getCanonicalPath();
          if(!visitedFiles.add("FS|"+absolute))continue;
          FileEntry entry=new FileEntry();entry.path=absolute;entry.tree="GLOBAL";entry.parent=directory.getAbsolutePath();
          entry.id=absolute;entry.name=c.getName();entry.folder=directory.getAbsolutePath().replace(base.getAbsolutePath(),"");
          entry.mime=mime(entry.name);entry.key="FS|"+absolute;entry.category=type;
          entry.size=c.length();entry.modified=c.lastModified();files.add(entry);
        }catch(Exception ex){if(issues.size()<100)issues.add(ex.getMessage());}
      }
      if(files.size()>0&&files.size()%1000==0)update("Indexing shared storage: "+files.size()+" files");
    }
  }
  boolean removeFile(FileEntry f)throws Exception{
    if(f.path!=null){
      File source=new File(f.path);
      if(!source.exists())throw new FileNotFoundException("File disappeared");
      return source.delete();
    }
    return DocumentsContract.deleteDocument(getContentResolver(),f.documentUri());
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
    ArrayList<FileEntry> chosen=new ArrayList<>();
    for(FileEntry f:files)if(selected.contains(f.key) && (!delete||safeCandidate(f)))chosen.add(f);
    if(chosen.isEmpty()){new AlertDialog.Builder(this).setTitle("No eligible copies selected")
      .setMessage("For safety, deletion is restricted to extra SHA-256-identical copies. Use SELECT ALL EXTRAS to choose them without selecting originals.")
      .setPositiveButton("OK",null).show();return;}
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
        if(delete){
          if(!safeCandidate(f))throw new IOException("Safety lock: original or non-identical file");
          String expected=f.hashKey;
          if(expected==null)throw new IOException("No verified hash");
          ArrayList<FileEntry> group=dupGroups.get(expected);
          if(group==null||group.size()<2)throw new IOException("Duplicate group no longer available");
          FileEntry keeper=group.get(0);
          if(keeper.key.equals(f.key))throw new IOException("Original is locked");
          if(!expected.equals(f.size+":"+hash(f.documentUri())))throw new IOException("Candidate changed after scan");
          if(!expected.equals(keeper.size+":"+hash(keeper.documentUri())))throw new IOException("Original changed or is missing");
          if(!removeFile(f))throw new IOException("Delete was denied");
        }
        else moveVerified(f,destination,bucket,byCategory);
        done++;if(done%10==0)update("Processed "+done+" of "+total+" files…");
      }catch(Exception e){if(errors.size()<20)errors.add(f.name+": "+e.getMessage());}
      final int success=done;
      runOnUiThread(()->{working=false;files.clear();selected.clear();duplicate.clear();older.clear();dupGroups.clear();revGroups.clear();
        protectedKeepers.clear();message=success+" of "+total+" processed. Run a new scan to refresh file groups.";render();
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
    c.sourceTree=f.tree;c.sourceParent=f.parent;c.sourcePath=f.path;c.originalName=f.name;c.reviewName=name;c.size=f.size;c.created=System.currentTimeMillis();c.bucket=bucket;
    // Journal BEFORE source deletion. A crash during transfer leaves an extra file, never an untracked loss.
    ArrayList<FileCopy> records=history();records.add(0,c);
    try{saveHistory(records);}catch(Exception ex){
      try{DocumentsContract.deleteDocument(getContentResolver(),created);}catch(Exception ignored){}
      throw new IOException("Could not record restore history; original kept",ex);
    }
    if(!removeFile(f)){
      boolean copyRemoved=false;try{copyRemoved=DocumentsContract.deleteDocument(getContentResolver(),created);}catch(Exception ignored){}
      if(copyRemoved){records.remove(0);saveHistory(records);}
      throw new IOException("Source could not be deleted. Original remains; any review copy was retained for safety.");
    }
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
    if(!prefs().edit().putString("history",a.toString()).commit())throw new IllegalStateException("History journal write failed");
  }
  void restoreConfirm(FileCopy e){new AlertDialog.Builder(this).setTitle("Restore file?")
    .setMessage(e.originalName+"\nRestores to original folder. Conflicting names are renamed, never overwritten.")
    .setNegativeButton("Cancel",null).setPositiveButton("RESTORE",(d,w)->{
      working=true;update("Restoring file…");
      jobs.submit(()->{String error=null;try{
        Uri original=doc(e.tree,e.doc);Uri created=null;
        if(e.sourcePath!=null&&!e.sourcePath.isEmpty()){
          File proposed=new File(e.sourcePath);
          File restore=proposed.exists()?new File(proposed.getParentFile(),"RESTORED_"+System.currentTimeMillis()+"_"+e.originalName):proposed;
          if(!restore.getParentFile().exists())throw new IOException("Original folder no longer exists");
          created=Uri.fromFile(restore);
          try{copyVerified(original,created);}catch(Exception x){restore.delete();throw x;}
        }else{
          final boolean[] exists={false};children(e.sourceTree,e.sourceParent,(id,n,m,s,t)->{if(n.equals(e.originalName))exists[0]=true;});
          String outName=exists[0]?"RESTORED_"+System.currentTimeMillis()+"_"+e.originalName:e.originalName;
          created=DocumentsContract.createDocument(getContentResolver(),doc(e.sourceTree,e.sourceParent),mime(e.originalName),outName);
          if(created==null)throw new IOException("Unable to create restored file");
          try{copyVerified(original,created);}catch(Exception x){try{DocumentsContract.deleteDocument(getContentResolver(),created);}catch(Exception ignored){}throw x;}
        }
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
