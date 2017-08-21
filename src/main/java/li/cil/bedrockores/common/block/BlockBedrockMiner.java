package li.cil.bedrockores.common.block;

import li.cil.bedrockores.common.config.Constants;
import li.cil.bedrockores.common.tileentity.TileEntityBedrockMiner;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

public class BlockBedrockMiner extends Block {
    public BlockBedrockMiner() {
        super(Material.IRON);
        setHardness(5);
        setResistance(10);
        setSoundType(SoundType.METAL);
    }

    @Override
    public boolean hasTileEntity(final IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(final World world, final IBlockState state) {
        return new TileEntityBedrockMiner();
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(final ItemStack stack, final EntityPlayer player, final List<String> tooltip, final boolean advanced) {
        super.addInformation(stack, player, tooltip, advanced);
        tooltip.add(new TextComponentTranslation(Constants.TOOLTIP_BEDROCK_MINER).getFormattedText());
    }
}