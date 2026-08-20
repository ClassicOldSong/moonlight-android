package com.limelight.profiles;

import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.EditProfileActivity;
import com.limelight.R;
import com.limelight.ui.AppDialog;

import java.util.List;
import java.util.UUID;

public class ProfilesAdapter extends RecyclerView.Adapter<ProfilesAdapter.ProfileViewHolder> {
    private final Context context;
    private final ProfilesManager profilesManager;

    public ProfilesAdapter(Context context) {
        this.context = context;
        this.profilesManager = ProfilesManager.getInstance();
    }

    @NonNull
    @Override
    public ProfileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.row_profile, parent, false);
        return new ProfileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ProfileViewHolder holder, int position) {
        List<SettingsProfile> profiles = profilesManager.getProfiles();
        SettingsProfile profile = profiles.get(position);
        SettingsProfile activeProfile = profilesManager.getActive();

        holder.profileName.setText(profile.getName());
        holder.profileTimestamp.setText(DateUtils.getRelativeTimeSpanString(
            profile.getModifiedUtc(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));

        boolean isActive = activeProfile != null && activeProfile.getUuid().equals(profile.getUuid());
        holder.profileActive.setChecked(isActive);

        // Set click listeners
        holder.profileActive.setOnClickListener(v -> {
            if (isActive) {
                profilesManager.setActive(null);
                Toast.makeText(context, R.string.profile_manager_deactivated_profile, Toast.LENGTH_SHORT).show();
            } else {
                profilesManager.setActive(profile.getUuid());
                Toast.makeText(context, context.getString(R.string.profile_manager_activated_profile, profile.getName()), Toast.LENGTH_SHORT).show();
            }
            profilesManager.save(context);
        });

        holder.editProfile.setOnClickListener(v -> {
            Intent intent = new Intent(context, EditProfileActivity.class);
            intent.putExtra("profileUuid", profile.getUuid().toString());
            context.startActivity(intent);
        });

        holder.deleteProfile.setOnClickListener(v -> {
            AppDialog.builder(context)
                    .setTitle(R.string.profile_manager_delete_profile)
                    .setMessage(context.getString(
                            R.string.profile_manager_confirm_profile_deleteion, profile.getName()))
                    .setNegativeButton(R.string.cancel, dialog -> true)
                    .setPositiveButton(R.string.profile_manager_delete, dialog -> {
                        profilesManager.delete(profile.getUuid());
                        profilesManager.save(context);
                        Toast.makeText(context, context.getString(
                                R.string.profile_manager_profile_deleted, profile.getName()),
                                Toast.LENGTH_SHORT).show();
                        return true;
                    })
                    .show();
        });

        // Also make the whole row clickable to edit
        holder.itemView.setOnClickListener(v -> holder.editProfile.performClick());
    }

    @Override
    public int getItemCount() {
        return profilesManager.getProfiles().size();
    }

    static class ProfileViewHolder extends RecyclerView.ViewHolder {
        TextView profileName;
        TextView profileTimestamp;
        RadioButton profileActive;
        ImageButton editProfile;
        ImageButton deleteProfile;

        ProfileViewHolder(@NonNull View itemView) {
            super(itemView);
            profileName = itemView.findViewById(R.id.profileName);
            profileTimestamp = itemView.findViewById(R.id.profileTimestamp);
            profileActive = itemView.findViewById(R.id.profileActive);
            editProfile = itemView.findViewById(R.id.editProfile);
            deleteProfile = itemView.findViewById(R.id.deleteProfile);
        }
    }
}