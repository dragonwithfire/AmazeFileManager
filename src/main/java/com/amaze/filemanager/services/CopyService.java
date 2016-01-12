/*
 * Copyright (C) 2014 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.amaze.filemanager.ProgressListener;
import com.amaze.filemanager.R;
import com.amaze.filemanager.RegisterCallback;
import com.amaze.filemanager.activities.MainActivity;
import com.amaze.filemanager.utils.BaseFile;
import com.amaze.filemanager.utils.DataPackage;
import com.amaze.filemanager.utils.Futils;
import com.amaze.filemanager.utils.HFile;
import com.amaze.filemanager.utils.RootHelper;
import com.stericson.RootTools.RootTools;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;

public class CopyService extends Service {
    HashMap<Integer, Boolean> hash = new HashMap<Integer, Boolean>();
    public HashMap<Integer, DataPackage> hash1 = new HashMap<Integer, DataPackage>();
    boolean rootmode;
    NotificationManager mNotifyManager;
    NotificationCompat.Builder mBuilder;
    Context c;
    Futils utils ;
    @Override
    public void onCreate() {
        c = getApplicationContext();
        utils=new Futils();
        SharedPreferences Sp=PreferenceManager.getDefaultSharedPreferences(this);
        rootmode=Sp.getBoolean("rootmode",false);
        registerReceiver(receiver3, new IntentFilter("copycancel"));
    }


    boolean foreground=true;
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Bundle b = new Bundle();
        ArrayList<String> files = intent.getStringArrayListExtra("FILE_PATHS");
        ArrayList<String> names=intent.getStringArrayListExtra("FILE_NAMES");
        String FILE2 = intent.getStringExtra("COPY_DIRECTORY");
        mNotifyManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        b.putInt("id", startId);
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setAction(Intent.ACTION_MAIN);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        notificationIntent.putExtra("openprocesses",true);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        mBuilder = new NotificationCompat.Builder(c);
        mBuilder.setContentIntent(pendingIntent);
        mBuilder.setContentTitle(getResources().getString(R.string.copying))

                .setSmallIcon(R.drawable.ic_content_copy_white_36dp);
        if(foreground){
            startForeground(Integer.parseInt("456"+startId),mBuilder.build());
            foreground=false;
        }
        b.putBoolean("move", intent.getBooleanExtra("move", false));
        b.putString("FILE2", FILE2);
        b.putStringArrayList("names",names);
        b.putStringArrayList("files", files);
        hash.put(startId, true);
        DataPackage intent1 = new DataPackage();
        intent1.setName(files.get(0));
        intent1.setTotal(0);
        intent1.setDone(0);
        intent1.setId(startId);
        intent1.setP1(0);
        intent1.setP2(0);
        intent1.setMove(intent.getBooleanExtra("move", false));
        intent1.setCompleted(false);
        hash1.put(startId,intent1);
        //going async
        new DoInBackground().execute(b);

        // If we get killed, after returning from here, restart
        return START_STICKY;
    }
    ProgressListener progressListener;


    public void onDestroy() {
        this.unregisterReceiver(receiver3);
    }

    public class DoInBackground extends AsyncTask<Bundle, Void, Integer> {
        ArrayList<String> files,names;
        boolean move;
        public DoInBackground() {
        }

        protected Integer doInBackground(Bundle... p1) {
            String FILE2 = p1[0].getString("FILE2");
            int id = p1[0].getInt("id");
            files = p1[0].getStringArrayList("files");
            names=p1[0].getStringArrayList("names");
            move=p1[0].getBoolean("move");
            new Copy().execute(id, files,names, FILE2,move);

            // TODO: Implement this method
            return id;
        }

        @Override
        public void onPostExecute(Integer b) {
            publishResults("", 0, 0, b, 0, 0, true, move);
            hash.put(b,false);
            boolean stop=true;
            for(int a:hash.keySet()){
            if(hash.get(a))stop=false;
            }
            if(stop)
            stopSelf(b);

        }

    }
    class Copy {

        long totalBytes = 0L, copiedBytes = 0L;
        boolean calculatingTotalSize=false;
        ArrayList<String> failedFOps;
        ArrayList<String> toDelete;
        boolean copy_successful;
        public Copy() {
            copy_successful=true;
            failedFOps=new ArrayList<>();
            toDelete=new ArrayList<>();
        }

        long getTotalBytes(final ArrayList<String> files) {
            calculatingTotalSize=true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    long totalBytes = 0l;
                    try {
                        for (int i = 0; i < files.size(); i++) {
                            HFile f1 = new HFile(files.get(i));
                            if (f1.isDirectory()) {
                                totalBytes = totalBytes + f1.folderSize();
                            } else {
                                totalBytes = totalBytes + f1.length();
                            }
                        }
                    } catch (Exception e) {
                    }
                    Copy.this.totalBytes=totalBytes;
                calculatingTotalSize=false;
                }
            }).run();

            return totalBytes;
        }
        public void execute(int id, final ArrayList<String> files,ArrayList<String> names, final String FILE2, final boolean move) {
            if (utils.checkFolder((FILE2), c) == 1) {
                getTotalBytes(files);
                for (int i = 0; i < files.size(); i++) {
                    HFile f1 = new HFile(files.get(i));
                    try {

                        if (hash.get(id)){
                            if(!f1.isSmb() && !new File(files.get(i)).canRead() && rootmode){
                                copyRoot(files.get(i),names.get(i),FILE2,move);
                                continue;
                            }
                            copyFiles((f1), new HFile(FILE2, names.get(i),f1.isDirectory()), id, move);
                        }
                        else{
                            stopSelf(id);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        failedFOps.add(files.get(i));
                        for(int j=i+1;j<files.size();j++)failedFOps.add(files.get(j));
                        publishResults("" + e, 0, 0, id, 0, 0, false, move);
                        generateNotification(failedFOps,move);
                        stopSelf(id);
                    break;
                    }
                }

                Intent intent = new Intent("loadlist");
                sendBroadcast(intent);
            } else if (rootmode) {
                boolean m = true;
                for (int i = 0; i < files.size(); i++) {
                    String path=files.get(i);
                    String name=names.get(i);
                    copyRoot(path,name,FILE2,move);
                    if(checkFiles(new HFile(path),new HFile(path+"/"+name))){
                        failedFOps.add(path);
                    }
                }
                if (move && m) {
                    ArrayList<String> toDelete=new ArrayList<>();
                    for(String a:files){
                        if(!failedFOps.contains(a))
                            toDelete.add(a);
                    }
                    new DeleteTask(getContentResolver(), c).execute((toDelete));
                }

                Intent intent = new Intent("loadlist");
                sendBroadcast(intent);

            } else {
                for(String f:files)
                    failedFOps.add(f);
            }
            generateNotification(failedFOps,move);
        }
        boolean copyRoot(String path,String name,String FILE2,boolean move){
            boolean b = RootTools.copyFile(RootHelper.getCommandLineString(path), RootHelper.getCommandLineString(FILE2)+"/"+name, true, true);
            if (!b && path.contains("/0/"))
                b = RootTools.copyFile(RootHelper.getCommandLineString(path.replace("/0/", "/legacy/")), RootHelper.getCommandLineString(FILE2)+"/"+name, true, true);
            utils.scanFile(FILE2 + "/" + name, c);
            return b;
        }

        boolean contains(String path){
            for(String a:failedFOps)if(a.contains(path))return true;
        return false;}
        private void copyFiles(final HFile sourceFile,final HFile targetFile,final int id,final boolean move) throws IOException {
            if (sourceFile.isDirectory()) {
                if(!hash.get(id))return;
                if (!targetFile.exists()) targetFile.mkdir(c);
                if(!targetFile.exists()){
                    failedFOps.add(sourceFile.getPath());
                    copy_successful=false;
                    return;
                }
                    targetFile.setLastModified(sourceFile.lastModified());
                if(!hash.get(id))return;
                ArrayList<BaseFile> filePaths = sourceFile.listFiles(false);
                for (BaseFile filePath : filePaths) {
                    HFile file=new HFile((filePath.getPath()));
                    HFile destFile = new HFile(targetFile.getPath(), file.getName(),file.isDirectory());
                    copyFiles(file, destFile, id, move);
                }
                if(!hash.get(id))return;
                if(move){
                    if(!contains(sourceFile.getPath())){
                        sourceFile.delete(c);
                    }
                }
            } else {
                if (!hash.get(id)) return;
                long size = sourceFile.length();
                InputStream in = sourceFile.getInputStream();
                OutputStream out = targetFile.getOutputStream(c);
                if (in == null || out == null) {
                    failedFOps.add(sourceFile.getPath());
                    copy_successful = false;
                    return;
                }
                if (!hash.get(id)) return;
                copy(in, out, size, id, sourceFile.getName(), move);
                new AsyncTask<Void, Void, Void>() {
                    @Override
                    protected Void doInBackground(Void... params) {
                        if(!checkNonRootFiles(sourceFile,targetFile)){
                            failedFOps.add(sourceFile.getPath());
                            copy_successful=false;
                        }
                        try {
                            targetFile.setLastModified(sourceFile.lastModified());
                        } catch (MalformedURLException e) {
                            e.printStackTrace();
                        } catch (SmbException e) {
                            e.printStackTrace();
                        }
                        if(!hash.get(id))return null;
                        if(move){
                            if(!failedFOps.contains(sourceFile.getPath())){
                                sourceFile.delete(c);
                            }
                        }
                        if(!targetFile.isSmb()) utils.scanFile(targetFile.getPath(), c);
                        return null;
                    }
                }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        }
        long time=System.nanoTime()/500000000;
        AsyncTask asyncTask;
        boolean checkNonRootFiles(HFile hFile1,HFile hFile2){
            long l1=hFile1.length(),l2=hFile2.length();
            if(hFile2.exists() && ((l1!=-1 && l2!=-1)?l1==l2:true)){
                //after basic checks try checksum if possible
                InputStream inputStream=hFile1.getInputStream();
                InputStream inputStream1=hFile2.getInputStream();
                if(inputStream==null || inputStream1==null)return true;
                String md5,md5_1;
                try {
                    md5=getMD5Checksum(inputStream);
                    md5_1=getMD5Checksum(inputStream1);
                    if(md5!=null && md5_1!=null && md5.length()>0 && md5_1.length()>0){
                        if(md5.equals(md5_1))return true;
                        else return false;
                    }else return true;
                } catch (Exception e) {
                    return true;
                }
            }
            return false;
        }
        public  String getMD5Checksum(InputStream filename) throws Exception {
            byte[] b = createChecksum(filename);
            String result = "";

            for (int i = 0; i < b.length; i++) {
                result += Integer.toString((b[i] & 0xff) + 0x100, 16).substring(1);
            }
            return result;
        }

        public  byte[] createChecksum(InputStream fis) throws Exception {
            byte[] buffer = new byte[8192];
            MessageDigest complete = MessageDigest.getInstance("MD5");
            int numRead;

            do {
                numRead = fis.read(buffer);
                if (numRead > 0) {
                    complete.update(buffer, 0, numRead);
                }
            } while (numRead != -1);

            fis.close();
            return complete.digest();
        }
        void calculateProgress(final String name, final long fileBytes, final int id, final long
                size, final boolean move) {
            if (asyncTask != null && asyncTask.getStatus() == AsyncTask.Status.RUNNING)
                asyncTask.cancel(true);
                asyncTask = new AsyncTask<Void, Void, Void>() {
                int p1, p2;

                @Override
                protected Void doInBackground(Void... voids) {
                    p1 = (int) ((copiedBytes / (float) totalBytes) * 100);
                    p2 = (int) ((fileBytes / (float) size) * 100);
                    if(calculatingTotalSize)p1=0;
                    return null;
                }

                @Override
                public void onPostExecute(Void v) {
                    publishResults(name, p1, p2, id, totalBytes, copiedBytes, false, move);
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
        void copy(InputStream stream,OutputStream outputStream,long size,int id,String name,boolean move) throws IOException {
            long  fileBytes = 0l;
            BufferedInputStream in = new BufferedInputStream(stream);
            BufferedOutputStream out=new BufferedOutputStream(outputStream);
            byte[] buffer = new byte[1024*60];
            int length;
            //copy the file content in bytes
            while ((length = in.read(buffer)) > 0) {
                boolean b = hash.get(id);
                if (b) {
                    out.write(buffer, 0, length);
                    copiedBytes += length;
                    fileBytes += length;
                    long time1=System.nanoTime()/500000000;
                    if(((int)time1)>((int)(time))){
                        calculateProgress(name,fileBytes,id,size,move);
                        time=System.nanoTime()/500000000;
                    }

                } else {
                    publishCompletedResult(id, Integer.parseInt("456" + id));
                    in.close();
                    out.close();
                    stopSelf(id);
                    return;
                }}
            in.close();
            out.close();
            stream.close();
            outputStream.close();
        }
    }


    void generateNotification(ArrayList<String> failedOps,boolean move) {
        if(failedOps.size()==0)return;
                mNotifyManager.cancelAll();
        NotificationCompat.Builder mBuilder=new NotificationCompat.Builder(c);
        mBuilder.setContentTitle("Operation Unsuccessful");
        mBuilder.setContentText("Some files weren't %s successfully".replace("%s",move?"moved":"copied"));
        Intent intent= new Intent(this, MainActivity.class);
        intent.putExtra("failedOps",failedOps);
        intent.putExtra("move",move);
        PendingIntent pIntent = PendingIntent.getActivity(this, (int)Math.random(), intent, 0);
        mBuilder.setContentIntent(pIntent);
        mBuilder.setSmallIcon(R.drawable.ic_content_copy_white_36dp);
        mNotifyManager.notify(741,mBuilder.build());
        intent=new Intent("general_communications");
        intent.putExtra("failedOps",failedOps);
        intent.putExtra("move",move);
        sendBroadcast(intent);
    }

    private void publishResults(String a, int p1, int p2, int id, long total, long done, boolean b, boolean move) {
        if (hash.get(id)) {
            //notification
            mBuilder.setProgress(100, p1, false);
            mBuilder.setOngoing(true);
            int title = R.string.copying;
            if (move) title = R.string.moving;
            mBuilder.setContentTitle(utils.getString(c, title));
            mBuilder.setContentText(new File(a).getName() + " " + utils.readableFileSize(done) + "/" + utils.readableFileSize(total));
            int id1 = Integer.parseInt("456" + id);
            mNotifyManager.notify(id1, mBuilder.build());
            if (p1 == 100 || total == 0) {
                mBuilder.setContentTitle("Copy completed");
                if (move)
                    mBuilder.setContentTitle("Move Completed");
                mBuilder.setContentText("");
                mBuilder.setProgress(0, 0, false);
                mBuilder.setOngoing(false);
                mBuilder.setAutoCancel(true);
                mNotifyManager.notify(id1, mBuilder.build());
                publishCompletedResult(id, id1);
            }
            //for processviewer
            DataPackage intent = new DataPackage();
            intent.setName(a);
            intent.setTotal(total);
            intent.setDone(done);
            intent.setId(id);
            intent.setP1(p1);
            intent.setP2(p2);
            intent.setMove(move);
            intent.setCompleted(b);
            hash1.put(id,intent);
            try {
                if(progressListener!=null){
                    progressListener.onUpdate(intent);
                    if(b)progressListener.refresh();
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        } else publishCompletedResult(id, Integer.parseInt("456" + id));
    }
    public void publishCompletedResult(int id,int id1){
        try {
            mNotifyManager.cancel(id1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //check if copy is successful
    boolean checkFiles(HFile hFile1,HFile hFile2){
        if(RootHelper.isDirectory(hFile1.getPath(),rootmode,5))
        {
            if(RootHelper.fileExists(hFile2.getPath()))return false;
            ArrayList<BaseFile> baseFiles=RootHelper.getFilesList(hFile1.getPath(),true,true);
            if(baseFiles.size()>0){
                boolean b=true;
                for(BaseFile baseFile:baseFiles){
                  if(!  checkFiles(new HFile(baseFile.getPath()),new HFile(hFile2.getPath()+"/"+new File(baseFile.getPath()).getName())))
                      b=false;
                }
                return b;
            }
            return RootHelper.fileExists(hFile2.getPath());
        }
        else{
          ArrayList<BaseFile>  baseFiles=RootHelper.getFilesList(hFile1.getParent(),true,true);
            int i=-1;
            int index=-1;
            for(BaseFile b:baseFiles){
                i++;
                if(b.getPath().equals(hFile1.getPath()))
                {   index=i;
                    break;
                }
            }
            ArrayList<BaseFile>  baseFiles1=RootHelper.getFilesList(hFile1.getParent(),true,true);
            int i1=-1;
            int index1=-1;
            for(BaseFile b:baseFiles1){
                i1++;
                if(b.getPath().equals(hFile1.getPath()))
                {   index1=i1;
                    break;
                }
            }
            if(baseFiles.get(index).getSize()==baseFiles1.get(index1).getSize())
                return true;
            else return false;
        }
    }
    private BroadcastReceiver receiver3 = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            //cancel operation
            hash.put(intent.getIntExtra("id", 1), false);
        }
    };
    //bind with processviewer
    RegisterCallback registerCallback= new RegisterCallback.Stub() {
        @Override
        public void registerCallBack(ProgressListener p) throws RemoteException {
            progressListener=p;
        }

        @Override
        public List<DataPackage> getCurrent() throws RemoteException {
            List<DataPackage> dataPackages=new ArrayList<>();
            for (int i : hash1.keySet()) {
               dataPackages.add(hash1.get(i));
            }
            return dataPackages;
        }
    };
    @Override
    public IBinder onBind(Intent arg0) {
        // TODO Auto-generated method stub
        return registerCallback.asBinder();
    }
}
