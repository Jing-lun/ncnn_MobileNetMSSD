package com.example.jinglun.mobilenetssd_demo;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;


public class MainActivity extends AppCompatActivity implements View.OnClickListener{

    private static final String TAG = MainActivity.class.getName();
    private static final int USE_PHOTO = 1001;
    private static final int USE_CAMERA = 1002;
    private String camera_image_path;
    private ImageView show_image;
    private TextView result_text;
    private boolean load_result = false;
    private int[] ddims = {1, 3, 300, 300}; //这里的维度的值要和train model的input 一一对应
    private int model_index = 1;
    private List<String> resultLabel = new ArrayList<>();
    private MobileNetssd mobileNetssd = new MobileNetssd(); //java接口实例化　下面直接利用java函数调用NDK c++函数
    public static File tempFile;
    private Uri imageUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        try
        {
            initMobileNetSSD();
        } catch (IOException e) {
            Log.e("MainActivity", "initMobileNetSSD error");
        }
        init_view();
        readCacheLabelFromLocalFile();
    }

    /**
     *
     * MobileNetssd初始化，也就是把model文件进行加载
     */
    private void initMobileNetSSD() throws IOException {
        byte[] param = null;
        byte[] bin = null;
        {
            //用io流读取二进制文件，最后存入到byte[]数组中
            InputStream assetsInputStream = getAssets().open("MobileNetSSD_deploy.param.bin");// param：  网络结构文件
            int available = assetsInputStream.available();
            param = new byte[available];
            int byteCode = assetsInputStream.read(param);
            assetsInputStream.close();
        }
        {
            //用io流读取二进制文件，最后存入到byte上，转换为int型
            InputStream assetsInputStream = getAssets().open("MobileNetSSD_deploy.bin");//bin：   model文件
            int available = assetsInputStream.available();
            bin = new byte[available];
            int byteCode = assetsInputStream.read(bin);
            assetsInputStream.close();
        }

        load_result = mobileNetssd.Init(param, bin);// 再将文件传入java的NDK接口(c++ 代码中的init接口 )
        Log.d("load model", "MobileNetSSD_load_model_result:" + load_result);
    }


    // initialize view
    private void init_view() {
        request_permissions();
        show_image = (ImageView) findViewById(R.id.show_image);
        result_text = (TextView) findViewById(R.id.result_text);
        result_text.setMovementMethod(ScrollingMovementMethod.getInstance());
        Button use_photo = (Button) findViewById(R.id.use_photo);
        use_photo.setOnClickListener(this);
        Button use_cam = (Button) findViewById(R.id.use_cam);
        use_cam.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.use_photo:
                if (!load_result) {
                    Toast.makeText(MainActivity.this, "never load model", Toast.LENGTH_SHORT).show();
                    return;
                }
                PhotoUtil.use_photo(MainActivity.this, USE_PHOTO);
                break;
            case R.id.use_cam:
                openCamera(this);
                break;
        }
    }

    // load label's name
    private void readCacheLabelFromLocalFile() {
        try {
            AssetManager assetManager = getApplicationContext().getAssets();
            BufferedReader reader = new BufferedReader(new InputStreamReader(assetManager.open("words.txt")));//这里是label的文件
            String readLine = null;
            while ((readLine = reader.readLine()) != null) {
                resultLabel.add(readLine);
            }
            reader.close();
        } catch (Exception e) {
            Log.e("labelCache", "error " + e);
        }
    }


    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        String image_path;
        RequestOptions options = new RequestOptions().skipMemoryCache(true).diskCacheStrategy(DiskCacheStrategy.NONE);
        if (resultCode == Activity.RESULT_OK) {
            switch (requestCode) {
                case USE_PHOTO:
                    if (data == null) {
                        Log.w(TAG, "user photo data is null");
                        return;
                    }
                    Uri image_uri = data.getData();

                    //Glide.with(MainActivity.this).load(image_uri).apply(options).into(show_image);

                    // get image path from uri
                    image_path = PhotoUtil.get_path_from_URI(MainActivity.this, image_uri);
                    // predict image
                    predict_image(image_path);
                    break;
                case USE_CAMERA:
                    if (resultCode == RESULT_OK) {
                        try {
                            if(data != null) {
                                imageUri = data.getData();
                            }
                            Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver()
                                    .openInputStream(imageUri));
                            predict_camera(bitmap);
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        }
                    }
                    break;
            }
        }
    }

    public void openCamera(Activity activity) {
        //獲取系統版本
        int currentapiVersion = android.os.Build.VERSION.SDK_INT;
        // 激活相机
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // 判断存储卡是否可以用，可用进行存储
        if (hasSdcard()) {
            String rootPath = Environment.getExternalStorageDirectory().getAbsolutePath();
            String name = System.currentTimeMillis() + ".jpg";
            tempFile = new File(rootPath + "/DCIM/camera", name);
            if (currentapiVersion < 24) {
                // 从文件中创建uri
                imageUri = Uri.fromFile(tempFile);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
            } else {
                //兼容android7.0 使用共享文件的形式
                ContentValues contentValues = new ContentValues(1);
                contentValues.put(MediaStore.Images.Media.DATA, tempFile.getAbsolutePath());
                //检查是否有存储权限，以免崩溃
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    //申请WRITE_EXTERNAL_STORAGE权限
                    Toast.makeText(this,"请开启存储权限",Toast.LENGTH_SHORT).show();
                    return;
                }
                imageUri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
            }
        }
        // 开启一个带有返回值的Activity，请求码为PHOTO_REQUEST_CAREMA
        activity.startActivityForResult(intent, USE_CAMERA);
    }

    /*
     * 判断sdcard是否被挂载
     */
    public static boolean hasSdcard() {
        return Environment.getExternalStorageState().equals(
                Environment.MEDIA_MOUNTED);
    }

    //  predict image
    private void predict_image(String image_path) {
        // picture to float array
        Bitmap bmp = PhotoUtil.getScaleBitmap(image_path);
        Bitmap rgba = bmp.copy(Bitmap.Config.ARGB_8888, true);
        // resize
        Bitmap input_bmp = Bitmap.createScaledBitmap(rgba, ddims[2], ddims[3], false);
        try {
            // Data format conversion takes too long
            // Log.d("inputData", Arrays.toString(inputData));
            long start = System.currentTimeMillis();
            // get predict result
            float[] result = mobileNetssd.Detect(input_bmp);
            // time end
            long end = System.currentTimeMillis();
            Log.d(TAG, "origin predict result:" + Arrays.toString(result));
            long time = end - start;
            Log.d("result length", "length of result: " + String.valueOf(result.length));
            // show predict result and time
//             float[] r = get_max_result(result);
            int num = result.length/6;// number of object
            float get_finalresult[][] = TwoArry(result);

            String show_text = "";
            for(int i=0; i<num; i++){
                show_text += "\nname：" + resultLabel.get((int) result[6*i+0]) + "\nprobability：" + result[6*i+1];
            }

            String time_text = "\ntime：" + time + "ms";
            String final_text = show_text + time_text;
            result_text.setText(final_text);

            // 画布配置
            Canvas canvas = new Canvas(rgba);
            //图像上画矩形
            Paint paint = new Paint();
            paint.setColor(Color.RED);
            paint.setStyle(Paint.Style.STROKE);//不填充
            paint.setStrokeWidth(5); //线的宽度



            Log.d("zhuanhuan",get_finalresult+"");
            int object_num = 0;

            //continue to draw rect
            for(object_num = 0; object_num < num; object_num++){
                Log.d(TAG, "haha :" + Arrays.toString(get_finalresult));
                // 画框
                paint.setColor(Color.RED);
                paint.setStyle(Paint.Style.STROKE);//不填充
                paint.setStrokeWidth(5); //线的宽度
                canvas.drawRect(get_finalresult[object_num][2] * rgba.getWidth(), get_finalresult[object_num][3] * rgba.getHeight(),
                        get_finalresult[object_num][4] * rgba.getWidth(), get_finalresult[object_num][5] * rgba.getHeight(), paint);

                paint.setColor(Color.YELLOW);
                paint.setStyle(Paint.Style.FILL);//不填充
                paint.setStrokeWidth(1); //线的宽度
                canvas.drawText(resultLabel.get((int) get_finalresult[object_num][0]) + "\n" + get_finalresult[object_num][1],
                        get_finalresult[object_num][2]*rgba.getWidth(),get_finalresult[object_num][3]*rgba.getHeight(),paint);
//                show_text = "result：" + Arrays.toString(result) + "\nname：" + resultLabel.get((int) result[object_num-1]) + "\nprobability：" + result[object_num];
//                show_text += "name：" + resultLabel.get((int) result[object_num-1]) + "\nprobability：" + result[object_num] + "\ntime：" + time + "ms";
            }
//            show_text += "\ntime：" + time + "ms";
//            result_text.setText(show_text);
            show_image.setImageBitmap(rgba);


        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void predict_camera(Bitmap bmp) {
        // picture to float array
        Bitmap rgba = bmp.copy(Bitmap.Config.ARGB_8888, true);
        // resize
        Bitmap input_bmp = Bitmap.createScaledBitmap(rgba, ddims[2], ddims[3], false);
        try {
            // Data format conversion takes too long
            // Log.d("inputData", Arrays.toString(inputData));
            long start = System.currentTimeMillis();
            // get predict result
            float[] result = mobileNetssd.Detect(input_bmp);
            // time end
            long end = System.currentTimeMillis();
            Log.d(TAG, "origin predict result:" + Arrays.toString(result));
            long time = end - start;
            Log.d("result length", "length of result: " + String.valueOf(result.length));
            // show predict result and time
//             float[] r = get_max_result(result);
            int num = result.length/6;// number of object
            float get_finalresult[][] = TwoArry(result);

            String show_text = "";
            for(int i=0; i<num; i++){
                show_text += "\nname：" + resultLabel.get((int) result[6*i+0]) + "\nprobability：" + result[6*i+1];
            }

            String time_text = "\ntime：" + time + "ms";
            String final_text = show_text + time_text;
            result_text.setText(final_text);

            // 画布配置
            Canvas canvas = new Canvas(rgba);
            //图像上画矩形
            Paint paint = new Paint();
            paint.setColor(Color.RED);
            paint.setStyle(Paint.Style.STROKE);//不填充
            paint.setStrokeWidth(5); //线的宽度



            Log.d("zhuanhuan",get_finalresult+"");
            int object_num = 0;

            //continue to draw rect
            for(object_num = 0; object_num < num; object_num++){
                Log.d(TAG, "haha :" + Arrays.toString(get_finalresult));
                // 画框
                paint.setColor(Color.RED);
                paint.setStyle(Paint.Style.STROKE);//不填充
                paint.setStrokeWidth(5); //线的宽度
                canvas.drawRect(get_finalresult[object_num][2] * rgba.getWidth(), get_finalresult[object_num][3] * rgba.getHeight(),
                        get_finalresult[object_num][4] * rgba.getWidth(), get_finalresult[object_num][5] * rgba.getHeight(), paint);

                paint.setColor(Color.YELLOW);
                paint.setStyle(Paint.Style.FILL);//不填充
                paint.setStrokeWidth(1); //线的宽度
                canvas.drawText(resultLabel.get((int) get_finalresult[object_num][0]) + "\n" + get_finalresult[object_num][1],
                        get_finalresult[object_num][2]*rgba.getWidth(),get_finalresult[object_num][3]*rgba.getHeight(),paint);
//                show_text = "result：" + Arrays.toString(result) + "\nname：" + resultLabel.get((int) result[object_num-1]) + "\nprobability：" + result[object_num];
//                show_text += "name：" + resultLabel.get((int) result[object_num-1]) + "\nprobability：" + result[object_num] + "\ntime：" + time + "ms";
            }
//            show_text += "\ntime：" + time + "ms";
//            result_text.setText(show_text);
            show_image.setImageBitmap(rgba);


        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //一维数组转化为二维数组(自己新写的)
    public static float[][] TwoArry(float[] inputfloat){
        int n = inputfloat.length;
        int num = inputfloat.length/6;
        float[][] outputfloat = new float[num][6];
        int k = 0;
        for(int i = 0; i < num ; i++)
        {
            int j = 0;

            while(j<6)
            {
                outputfloat[i][j] =  inputfloat[k];
                k++;
                j++;
            }

        }

        return outputfloat;
    }

    /*
    // get max probability label
    private float[] get_max_result(float[] result) {
        int num_rs = result.length / 6;
        float maxProp = result[1];
        int maxI = 0;
        for(int i = 1; i<num_rs;i++){
            if(maxProp<result[i*6+1]){
                maxProp = result[i*6+1];
                maxI = i;
            }
        }
        float[] ret = {0,0,0,0,0,0};
        for(int j=0;j<6;j++){
            ret[j] = result[maxI*6 + j];
        }
        return ret;
    }
    */
    // request permissions(add)
    private void request_permissions() {
        List<String> permissionList = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionList.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionList.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        // if list is not empty will request permissions
        if (!permissionList.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionList.toArray(new String[permissionList.size()]), 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case 1:
                if (grantResults.length > 0) {
                    for (int i = 0; i < grantResults.length; i++) {
                        int grantResult = grantResults[i];
                        if (grantResult == PackageManager.PERMISSION_DENIED) {
                            String s = permissions[i];
                            Toast.makeText(this, s + "permission was denied", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                break;
        }
    }



}


