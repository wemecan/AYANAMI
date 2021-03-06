package com.whoiszxl.product.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.whoiszxl.common.utils.IdWorker;
import com.whoiszxl.product.entity.*;
import com.whoiszxl.product.mapper.*;
import com.whoiszxl.product.service.SpuService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author author
 * @since 2020-03-20
 */
@Slf4j
@Service
@Transactional
public class SpuServiceImpl extends ServiceImpl<SpuMapper, Spu> implements SpuService {

    @Autowired
    private IdWorker idWorker;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private BrandMapper brandMapper;

    @Autowired
    private SkuMapper skuMapper;

    @Autowired
    private SpuMapper spuMapper;

    @Autowired
    private CategoryBrandMapper categoryBrandMapper;

    @Override
    public void add(Product product) {
        //1.添加spu
        Spu spu = product.getSpu();
        //设置分布式id
        long spuId = idWorker.nextId();
        spu.setId(String.valueOf(spuId));
        //设置删除状态.
        spu.setIsDelete("0");
        //上架状态
        spu.setIsMarketable("0");
        //审核状态
        spu.setStatus("0");
        spuMapper.insert(spu);

        //2.添加sku集合
        this.saveSkuList(product);
    }

    @Override
    public void update(Product product) {
        //取出spu部分
        Spu spu = product.getSpu();
        spuMapper.updateById(spu);

        //删除原sku列表
        QueryWrapper<Sku> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("spu_id", spu.getId());
        skuMapper.delete(queryWrapper);

        saveSkuList(product);//保存sku列表
    }


    @Override
    public Product findProductById(String id) {
        Spu spu = spuMapper.selectById(id);

        QueryWrapper<Sku> queryWrapper = new QueryWrapper<Sku>();
        queryWrapper.eq("spu_id", id);
        List<Sku> skuList = skuMapper.selectList(queryWrapper);

        Product product = new Product();
        product.setSpu(spu);
        product.setSkuList(skuList);
        return product;
    }

    @Transactional
    @Override
    public void audit(String id) {
        //查询spu对象
        Spu spu = spuMapper.selectById(id);
        if(spu == null) {
            throw new RuntimeException("当前商品不存在");
        }

        //TODO 抽取变量 判断当前商品状态是否是删除状态
        if("1".equals(spu.getIsDelete())) {
            throw new RuntimeException("商品已被删除");
        }

        //不处于删除状态,修改审核状态为1,上下架状态为1
        spu.setStatus("1");
        spu.setIsMarketable("1");

        spuMapper.updateById(spu);

    }

    @Override
    public void pull(String id) {
        //查询spu
        Spu spu = spuMapper.selectById(id);
        if (spu == null){
            throw new RuntimeException("当前商品不存在");
        }
        //判断当前商品是否处于删除状态
        if ("1".equals(spu.getIsDelete())){
            throw new RuntimeException("当前商品处于删除状态");
        }

        //商品处于未删除状态的话,则修改上下架状态为已下架(0)
        spu.setIsMarketable("0");
        spuMapper.updateById(spu);
    }

    @Override
    public void put(String id) {
        Spu spu = spuMapper.selectById(id);
        if(!"1".equals(spu.getStatus())) {
            throw new RuntimeException("未通过审核的商品不能上架！");
        }
        spu.setIsMarketable("1");
        spuMapper.updateById(spu);
    }

    @Override
    public void restore(String id) {
        Spu spu = spuMapper.selectById(id);
        //检查是否删除的商品
        if(!spu.getIsDelete().equals("1")){
            throw new RuntimeException("此商品未删除！");
        }
        spu.setIsDelete("0");//未删除
        spu.setStatus("0");//未审核
        spuMapper.updateById(spu);
    }

    @Override
    public void readDelete(String id) {
        Spu spu = spuMapper.selectById(id);
        //检查spu是否是软删除的商品
        if(!spu.getIsDelete().equals("1")) {
            throw new RuntimeException("此商品未删除！");
        }

        spuMapper.deleteById(id);
    }

    //添加sku数据
    private void saveSkuList(Product goods) {
        Spu spu = goods.getSpu();
        //查询分类对象
        Category category = categoryMapper.selectById(spu.getCategory3Id());

        //查询品牌对象
        Brand brand = brandMapper.selectById(spu.getBrandId());

        //设置品牌与分类的关联关系
        //查询关联表
        CategoryBrand categoryBrand = new CategoryBrand();
        categoryBrand.setBrandId(spu.getBrandId());
        categoryBrand.setCategoryId(spu.getCategory3Id());
        QueryWrapper<CategoryBrand> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("brand_id", spu.getBrandId());
        queryWrapper.eq("category_id", spu.getCategory3Id());
        int count = categoryBrandMapper.selectCount(queryWrapper);
        if (count == 0){
            //品牌与分类还没有关联关系
            categoryBrandMapper.insert(categoryBrand);
        }

        //获取sku集合
        List<Sku> skuList = goods.getSkuList();
        if (skuList != null){
            //遍历sku集合,循环填充数据并添加到数据库中
            for (Sku sku : skuList) {
                //设置skuId
                sku.setId(String.valueOf(idWorker.nextId()));
                //设置sku规格数据
                if (StringUtils.isEmpty(sku.getSpec())){
                    sku.setSpec("{}");
                }
                //设置sku名称(spu名称+规格)
                String name = spu.getName();
                //将规格json转换为map,将map中的value进行名称的拼接
                Map<String,String> specMap = JSON.parseObject(sku.getSpec(), Map.class);
                if (specMap != null && specMap.size()>0){
                    for (String value : specMap.values()) {
                        name+=" "+value;
                    }
                }
                sku.setName(name);
                //设置spuid
                sku.setSpuId(spu.getId());
                //设置创建与修改时间
                sku.setCreateTime(LocalDateTime.now());
                sku.setUpdateTime(LocalDateTime.now());
                //商品分类id
                sku.setCategoryId(category.getId());
                //设置商品分类名称
                sku.setCategoryName(category.getName());
                //设置品牌名称
                sku.setBrandName(brand.getName());
                //将sku添加到数据库
                skuMapper.insert(sku);

            }
        }
    }

}
