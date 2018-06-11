package com.github.axet.bookreader.fragments;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.github.axet.androidlibrary.crypto.MD5;
import com.github.axet.androidlibrary.widgets.AboutPreferenceCompat;
import com.github.axet.androidlibrary.widgets.CacheImagesAdapter;
import com.github.axet.bookreader.R;
import com.github.axet.bookreader.activities.MainActivity;
import com.github.axet.bookreader.app.BooksCatalog;
import com.github.axet.bookreader.app.BooksCatalogs;
import com.github.axet.bookreader.app.LocalBooksCatalog;
import com.github.axet.bookreader.app.Storage;
import com.github.axet.bookreader.widgets.BrowserDialogFragment;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.geometerplus.android.util.UIUtil;
import org.geometerplus.fbreader.formats.BookReadingException;
import org.geometerplus.fbreader.formats.FormatPlugin;
import org.geometerplus.fbreader.formats.PluginCollection;
import org.geometerplus.fbreader.network.tree.NetworkItemsLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class LocalLibraryFragment extends Fragment implements MainActivity.SearchListener {
    public static final String TAG = LocalLibraryFragment.class.getSimpleName();

    LibraryFragment.FragmentHolder holder;
    LocalLibraryAdapter books;
    Storage storage;
    LocalBooksCatalog n;
    String host;
    BooksCatalogs catalogs;
    Handler handler = new Handler();
    Runnable invalidateOptionsMenu = new Runnable() {
        @Override
        public void run() {
            ActivityCompat.invalidateOptionsMenu(getActivity());
        }
    };

    public static class Book {
        public Uri url;
        public String md5; // url md5, NOT book content
        public Storage.RecentInfo info;
        public File cover;
        public String folder;

        public Book(File root, File f) {
            url = Uri.fromFile(f);
            String p = root.getPath();
            String n = f.getPath();
            if (n.startsWith(p))
                n = n.substring(p.length());
            File m = new File(n);
            folder = m.getParent();
        }

        @TargetApi(21)
        public Book(Uri root, Uri u) {
            url = u;
            String p = DocumentsContract.getTreeDocumentId(root);
            String f = DocumentsContract.getDocumentId(u);
            if (f.startsWith(p))
                f = f.substring(p.length());
            File n = new File(f);
            folder = n.getParent();
        }
    }

    public class LocalLibraryAdapter extends LibraryFragment.BooksAdapter {
        List<Book> all = new ArrayList<>(); // all items
        List<Book> list = new ArrayList<>(); // filtered list
        String filter;

        public LocalLibraryAdapter() {
            super(LocalLibraryFragment.this.getContext());
        }

        @Override
        public int getLayout() {
            return holder.layout;
        }

        public void load() {
            Uri u = Uri.parse(n.url);
            load(u);
            books.filter = null;
        }

        void load(File f) {
            load(f, f);
        }

        void load(File root, File f) {
            File[] ff = f.listFiles();
            if (ff != null) {
                for (File k : ff) {
                    if (k.isDirectory()) {
                        load(root, k);
                    } else {
                        Storage.Detector[] dd = Storage.supported();
                        for (Storage.Detector d : dd) {
                            if (k.toString().toLowerCase(Locale.US).endsWith(d.ext)) {
                                books.all.add(new Book(root, k));
                                break;
                            }
                        }
                    }
                }
            }
        }

        @TargetApi(21)
        void load(Uri u, Uri childrenUri) {
            ContentResolver contentResolver = storage.getContext().getContentResolver();
            Cursor childCursor = contentResolver.query(childrenUri, null, null, null, null);
            if (childCursor != null) {
                try {
                    while (childCursor.moveToNext()) {
                        String id = childCursor.getString(childCursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                        String t = childCursor.getString(childCursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                        String type = childCursor.getString(childCursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE));
                        long size = childCursor.getLong(childCursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE));
                        if (type != null && type.equals(DocumentsContract.Document.MIME_TYPE_DIR)) {
                            Uri k = DocumentsContract.buildChildDocumentsUriUsingTree(u, id);
                            load(u, k);
                        } else if (size > 0) {
                            String n = t.toLowerCase(Locale.US);
                            Storage.Detector[] dd = Storage.supported();
                            for (Storage.Detector d : dd) {
                                if (n.endsWith(d.ext)) {
                                    Uri k = DocumentsContract.buildDocumentUriUsingTree(u, id);
                                    books.all.add(new Book(u, k));
                                    break;
                                }
                            }
                        }
                    }
                } finally {
                    childCursor.close();
                }
            }
        }

        void load(Uri u) {
            String s = u.getScheme();
            if (Build.VERSION.SDK_INT >= 21 && s.startsWith(ContentResolver.SCHEME_CONTENT)) {
                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(u, DocumentsContract.getTreeDocumentId(u));
                load(u, childrenUri);
            } else if (s.startsWith(ContentResolver.SCHEME_FILE)) {
                load(Storage.getFile(u));
            } else {
                throw new RuntimeException("unknow uri");
            }
        }

        public void refresh() {
            if (filter == null || filter.isEmpty()) {
                list = all;
                clearTasks();
            } else {
                list = new ArrayList<>();
                for (Book b : all) {
                    String t = null;
                    if (b.info != null)
                        t = b.info.title;
                    if (t == null || t.isEmpty())
                        t = storage.getDisplayName(b.url);
                    if (t.toLowerCase(Locale.US).contains(filter.toLowerCase(Locale.US))) {
                        list.add(b);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return list.size();
        }

        public Book getItem(int position) {
            return list.get(position);
        }

        @Override
        public String getAuthors(int position) {
            Book b = list.get(position);
            return b.info.authors;
        }

        @Override
        public String getTitle(int position) {
            Book b = list.get(position);
            return b.info.title;
        }

        @Override
        public Uri getCover(int position) {
            Book b = list.get(position);
            return Uri.fromFile(b.cover);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LayoutInflater inflater = LayoutInflater.from(getContext());
            if (convertView == null)
                convertView = inflater.inflate(getLayout(), null, false);

            Book b = list.get(position);
            if (b.info == null || b.cover == null || !b.cover.exists()) {
                downloadTask(b, convertView);
            } else {
                downloadTaskClean(convertView);
                downloadTaskUpdate(null, b, convertView);
            }
            return convertView;
        }

        @Override
        public void downloadTaskUpdate(CacheImagesAdapter.DownloadImageTask task, Object item, Object view) {
            super.downloadTaskUpdate(task, item, view);
            BookHolder h = new BookHolder((View) view);

            Book b = (Book) item;

            if (b.info != null) {
                setText(h.aa, b.info.authors);
                String t = b.info.title;
                if (t == null || t.isEmpty())
                    t = storage.getDisplayName(b.url);
                setText(h.tt, t);
            } else {
                setText(h.aa, "");
                setText(h.tt, storage.getDisplayName(b.url));
            }

            if (b.cover != null && b.cover.exists()) {
                ImageView image = (ImageView) ((View) view).findViewById(R.id.book_cover);
                try {
                    Bitmap bm = BitmapFactory.decodeStream(new FileInputStream(b.cover));
                    image.setImageBitmap(bm);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public void updateView(CacheImagesAdapter.DownloadImageTask task, ImageView image, ProgressBar progress) {
            super.updateView(task, image, progress);
        }

        @Override
        public Bitmap downloadImageTask(CacheImagesAdapter.DownloadImageTask task) {
            Book fbook = (Book) task.item;
            try {
                boolean tmp = false;
                InputStream is;
                OutputStream os = null;

                String md5 = MD5.digest(fbook.url.toString());
                fbook.md5 = md5;
                File r = recentFile(fbook);
                if (r.exists()) {
                    try {
                        fbook.info = new Storage.RecentInfo(r);
                    } catch (RuntimeException e) {
                        Log.d(TAG, "Unable to load info", e);
                    }
                }
                File cover = coverFile(fbook);
                if (fbook.info == null || !cover.exists() || cover.length() == 0) {
                    Storage.Book b = new Storage.Book();
                    String s = fbook.url.getScheme();
                    if (s.equals(ContentResolver.SCHEME_CONTENT)) {
                        tmp = true;
                        b.file = File.createTempFile("book", ".tmp", storage.getCache());
                        os = new FileOutputStream(b.file);
                        ContentResolver resolver = getContext().getContentResolver();
                        is = resolver.openInputStream(fbook.url);
                    } else if (s.equals(ContentResolver.SCHEME_FILE)) {
                        b.file = new File(fbook.url.getPath());
                        is = FileUtils.openInputStream(b.file);
                    } else {
                        throw new RuntimeException("unknown uri");
                    }

                    Storage.Detector[] dd = Storage.supported();

                    MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
                    Storage.FileTypeDetectorXml xml = new Storage.FileTypeDetectorXml(dd);
                    Storage.FileTypeDetectorZip zip = new Storage.FileTypeDetectorZip(dd);
                    Storage.FileTypeDetector bin = new Storage.FileTypeDetector(dd);

                    byte[] buf = new byte[Storage.BUF_SIZE];
                    int len;
                    while ((len = is.read(buf)) > 0) {
                        digest.update(buf, 0, len);
                        if (os != null)
                            os.write(buf, 0, len);
                        xml.write(buf, 0, len);
                        zip.write(buf, 0, len);
                        bin.write(buf, 0, len);
                    }

                    if (os != null)
                        os.close();
                    bin.close();
                    zip.close();
                    xml.close();

                    for (Storage.Detector d : dd) {
                        if (d.detected) {
                            b.ext = d.ext;
                            if (d instanceof Storage.FileTypeDetectorZipExtract.Handler) {
                                Storage.FileTypeDetectorZipExtract.Handler e = (Storage.FileTypeDetectorZipExtract.Handler) d;
                                File z = b.file;
                                File tt = File.createTempFile("book", ".tmp", storage.getCache());
                                e.extract(z, tt);
                                File nn = new File(n.getCache(), md5 + "." + b.ext);
                                Storage.move(tt, nn);
                                b.file = nn;
                                tmp = true;
                            } else if (tmp) {
                                File tt = new File(n.getCache(), md5 + "." + b.ext);
                                Storage.move(b.file, tt);
                                b.file = tt;
                            }
                            break; // priority first - more imporant
                        }
                    }

                    if (b.ext == null) {
                        all.remove(fbook);
                    } else {
                        try {
                            LocalLibraryFragment.this.load(fbook, b);
                        } catch (RuntimeException e) {
                            Log.d(TAG, "unable to load file", e);
                        }
                    }

                    if (tmp) {
                        b.file.delete();
                        b.file = null;
                    }
                } else {
                    fbook.cover = cover;
                }

                if (fbook.cover == null)
                    return null;

                return BitmapFactory.decodeStream(new FileInputStream(fbook.cover));
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public File coverFile(Book book) {
        return new File(n.getCache(), book.md5 + "." + Storage.COVER_EXT);
    }

    public File recentFile(Book book) {
        return new File(n.getCache(), book.md5 + "." + Storage.JSON_EXT);
    }

    public void load(final Book book, final Storage.Book fbook) {
        if (book.info == null) {
            File r = recentFile(book);
            if (r.exists())
                try {
                    book.info = new Storage.RecentInfo(r);
                } catch (RuntimeException e) {
                    Log.d(TAG, "Unable to load info", e);
                }
        }
        if (book.info == null) {
            book.info = new Storage.RecentInfo();
            book.info.created = System.currentTimeMillis();
        }
        book.info.md5 = book.md5;
        final PluginCollection pluginCollection = PluginCollection.Instance(new Storage.Info(storage.getContext()));
        Runnable read = new Runnable() {
            @Override
            public void run() {
                if (fbook.book != null)
                    return;
                fbook.book = new org.geometerplus.fbreader.book.Book(-1, fbook.file.getPath(), null, null, null);
                FormatPlugin plugin = Storage.getPlugin(pluginCollection, fbook);
                try {
                    plugin.readMetainfo(fbook.book);
                } catch (BookReadingException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        if (book.info.authors == null || book.info.authors.isEmpty()) {
            read.run();
            book.info.authors = fbook.book.authorsString(", ");
        }
        if (book.info.title == null || book.info.title.isEmpty() || book.info.title.equals(book.md5)) {
            read.run();
            book.info.title = Storage.getTitle(fbook);
        }
        if (book.cover == null) {
            File cover = coverFile(book);
            if (!cover.exists() || cover.length() == 0) {
                read.run();
                storage.createCover(fbook, cover);
            }
            book.cover = cover;
        }
        save(book);
    }

    public void save(Book book) {
        book.info.last = System.currentTimeMillis();
        File f = recentFile(book);
        try {
            String json = book.info.save().toString();
            Writer w = new FileWriter(f);
            IOUtils.write(json, w);
            w.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public LocalLibraryFragment() {
    }

    public static LocalLibraryFragment newInstance(String n) {
        LocalLibraryFragment fragment = new LocalLibraryFragment();
        Bundle args = new Bundle();
        args.putString("url", n);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        storage = new Storage(getContext());
        catalogs = new BooksCatalogs(getContext());
        final String u = getArguments().getString("url");
        holder = new LibraryFragment.FragmentHolder(getContext()) {
            @Override
            public String getLayout() {
                return MD5.digest(u);
            }
        };
        books = new LocalLibraryAdapter();
        n = (LocalBooksCatalog) catalogs.find(u);

        setHasOptionsMenu(true);

        loadDefault();
    }

    void loadDefault() {
        final MainActivity main = (MainActivity) getActivity();
        UIUtil.wait("loadingBookList", new Runnable() {
            @Override
            public void run() {
                try {
                    books.load();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            loadView();
                        }
                    });
                } catch (Exception e) {
                    main.Post(e);
                }
            }
        }, getContext());
    }

    void loadView() {
        loadBooks();
        loadtoolBar();
    }

    void loadBooks() {
        books.clearTasks();
        books.refresh();
    }

    void loadtoolBar() {
        holder.searchtoolbar.removeAllViews();
        if (holder.searchtoolbar.getChildCount() == 0)
            holder.toolbar.setVisibility(View.GONE);
        else
            holder.toolbar.setVisibility(View.VISIBLE);
    }

    void selectToolbar() {
        String id = getArguments().getString("toolbar");
        if (id == null)
            return;
        for (int i = 0; i < holder.searchtoolbar.getChildCount(); i++) {
            View v = holder.searchtoolbar.getChildAt(i);
            NetworkItemsLoader b = (NetworkItemsLoader) v.getTag();
            ImageButton k = (ImageButton) v.findViewById(R.id.toolbar_icon_image);
            if (b.Tree.getUniqueKey().Id.equals(id)) {
                int[] states = new int[]{
                        android.R.attr.state_checked,
                };
                k.setImageState(states, false);
            } else {
                int[] states = new int[]{
                        -android.R.attr.state_checked,
                };
                k.setImageState(states, false);
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View v = inflater.inflate(R.layout.fragment_library, container, false);

        final MainActivity main = (MainActivity) getActivity();

        holder.create(v);
        holder.footer.setVisibility(View.GONE);
        holder.footerNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UIUtil.wait("search", new Runnable() {
                    @Override
                    public void run() {
                    }
                }, getContext());
            }
        });

        main.toolbar.setTitle(R.string.app_name);
        holder.grid.setAdapter(books);
        holder.grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                try {
                    final Book b = books.getItem(position);
                    loadBook(b);
                } catch (RuntimeException e) {
                    main.Error(e);
                }
            }
        });
        return v;
    }

    void loadBook(final Book book) {
        final MainActivity main = (MainActivity) getActivity();
        main.loadBook(book.url, null);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        loadBooks();
        loadtoolBar();
        selectToolbar();
    }

    @Override
    public void onStart() {
        super.onStart();
        final MainActivity main = (MainActivity) getActivity();
        main.setFullscreen(false);
        main.restoreNetworkSelection(this);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    public void search(final String ss) {
        if (ss == null || ss.isEmpty())
            return;
        books.filter = ss;
        books.refresh();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
    }

    @Override
    public void onDetach() {
        super.onDetach();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        books.clearTasks();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    public void openBrowser(String u) {
        if (Build.VERSION.SDK_INT < 11) {
            AboutPreferenceCompat.openUrl(getContext(), u);
        } else {
            BrowserDialogFragment b = BrowserDialogFragment.create(u);
            b.show(getFragmentManager(), "");
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_home) {
            openBrowser(host);
            return true;
        }
        if (holder.onOptionsItemSelected(item)) {
            invalidateOptionsMenu.run();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateOptionsMenu(final Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);

        if (Build.VERSION.SDK_INT < 11) {
            invalidateOptionsMenu = new Runnable() {
                @Override
                public void run() {
                    onCreateOptionsMenu(menu, null);
                }
            };
        }

        MenuItem homeMenu = menu.findItem(R.id.action_home);
        MenuItem tocMenu = menu.findItem(R.id.action_toc);
        MenuItem searchMenu = menu.findItem(R.id.action_search);
        MenuItem reflow = menu.findItem(R.id.action_reflow);
        MenuItem fontsize = menu.findItem(R.id.action_fontsize);
        MenuItem debug = menu.findItem(R.id.action_debug);
        MenuItem rtl = menu.findItem(R.id.action_rtl);

        reflow.setVisible(false);
        fontsize.setVisible(false);
        debug.setVisible(false);
        rtl.setVisible(false);
        tocMenu.setVisible(false);

        searchMenu.setVisible(true);

        if (host == null || host.isEmpty())
            homeMenu.setVisible(false);
        else
            homeMenu.setVisible(true);
    }

    @Override
    public void searchClose() {
        books.filter = null;
        loadDefault();
        books.refresh();
    }

    @Override
    public String getHint() {
        return getString(R.string.search_local);
    }
}
